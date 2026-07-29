package com.jobseekercopilot.cvcoverletter.service;

import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validRequest;
import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validVersionedRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClaimEvidenceValidatorTest {
    private ObjectMapper objectMapper;
    private LlmResponseParser parser;
    private JsonNode schema;
    private ClaimEvidenceCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        parser = new LlmResponseParser(objectMapper, new ClaimEvidenceValidator());
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.3.0/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Claim evidence schema fixture is missing.");
            }
            schema = objectMapper.readTree(input);
        }
        catalog = new ClaimEvidenceCatalogFactory().create(
                new GenerationInputNormalizer(
                        Clock.fixed(Instant.parse("2026-07-24T13:00:00Z"), ZoneOffset.UTC))
                        .normalize("owner-secret", validRequest()));
    }

    @Test
    void acceptsCompleteLedgerAndKeepsReviewOnlyClaimsOutOfFinalContent() throws Exception {
        ObjectNode output = validOutput();
        ArrayNode claims = (ArrayNode) output.path("claims");
        claims.add(reviewOnlyClaim(
                "CLAIM-011",
                "CONFIRMATION_REQUIRED",
                "Please confirm notice period."));
        claims.add(reviewOnlyClaim(
                "CLAIM-012",
                "REJECTED",
                "Unsupported salary expectation was removed."));

        GeneratedApplicationDocuments result = parse(output);

        assertEquals(12, result.getClaims().size());
        assertEquals("A Java developer focused on useful services.",
                result.getCv().getPersonalSummary());
    }

    @Test
    void rejectsUnknownMissingDuplicateAndReviewOnlyFinalMappings() throws Exception {
        ObjectNode unknownEvidence = validOutput();
        ((ArrayNode) unknownEvidence.at("/claims/0/evidenceIds"))
                .set(0, objectMapper.getNodeFactory().textNode("PROFILE.UNKNOWN"));
        assertRejected(unknownEvidence, "evidence ID is not approved");

        ObjectNode missingCoverage = validOutput();
        ((ArrayNode) missingCoverage.path("claims")).remove(8);
        GeneratedApplicationDocuments normalizedMissingCoverage =
                parse(missingCoverage);
        assertTrue(normalizedMissingCoverage.getClaims().stream()
                .flatMap(claim -> claim.getContentPaths().stream())
                .anyMatch("/coverLetter/closingParagraph"::equals));
        assertFalse("Thank you for your consideration.".equals(
                normalizedMissingCoverage.getCoverLetter()
                        .getClosingParagraph()));

        ObjectNode duplicateCoverage = validOutput();
        ((ArrayNode) duplicateCoverage.at("/claims/1/contentPaths")).add("/cv/targetRole");
        GeneratedApplicationDocuments normalizedDuplicateCoverage =
                parse(duplicateCoverage);
        assertEquals(
                1,
                normalizedDuplicateCoverage.getClaims().stream()
                        .flatMap(claim -> claim.getContentPaths().stream())
                        .filter("/cv/targetRole"::equals)
                        .count());

        ObjectNode reviewOnlyFinalPath = validOutput();
        ObjectNode claim = (ObjectNode) reviewOnlyFinalPath.at("/claims/0");
        claim.put("disposition", "CONFIRMATION_REQUIRED");
        claim.put("reviewText", "Please confirm the target role.");
        assertRejected(reviewOnlyFinalPath, "review-only claim points at final content");
    }

    @Test
    void canonicalizesAtomicValuesToTheSingleCitedApprovedFact()
            throws Exception {
        assertUnsafePersonalSummary(
                "A Java developer who improved throughput by 37%.",
                "numeric claim is absent");
        assertUnsafePersonalSummary(
                "A Java developer experienced with Kubernetes.",
                "sensitive or specific claim is absent");
        assertUnsafePersonalSummary(
                "A Java developer with a PhD.",
                "sensitive or specific claim is absent");

        ObjectNode fabricatedTitle = validOutput();
        ((ObjectNode) fabricatedTitle.path("coverLetter")).put("jobTitle", "Senior Architect");
        GeneratedApplicationDocuments correctedTitle =
                parse(fabricatedTitle);
        assertEquals(
                "Java Developer",
                correctedTitle.getCoverLetter().getJobTitle());

        ObjectNode fabricatedQualification = validOutput();
        addQualification(fabricatedQualification, "PhD Computing", "2024");
        GeneratedApplicationDocuments correctedQualification =
                parse(fabricatedQualification);
        assertEquals(
                "BSc Computing",
                correctedQualification.getCv()
                        .getQualifications().get(0)
                        .getQualificationName());

        ObjectNode fabricatedDate = validOutput();
        addQualification(fabricatedDate, "BSc Computing", "2019");
        GeneratedApplicationDocuments correctedDate =
                parse(fabricatedDate);
        assertEquals(
                "2024",
                correctedDate.getCv()
                        .getQualifications().get(0)
                        .getDateAchieved());
    }

    @Test
    void rejectsAtomicContentWithoutOneCompatibleCitedFact()
            throws Exception {
        ObjectNode output = validOutput();
        ObjectNode skill = objectMapper.createObjectNode();
        skill.put("name", "Fabricated Platform");
        skill.put("evidence", "Fabricated evidence.");
        ((ArrayNode) output.at("/cv/coreSkills")).add(skill);
        ObjectNode claim = objectMapper.createObjectNode();
        claim.put("claimId", "CLAIM-011");
        claim.put("disposition", "SUPPORTED");
        claim.putArray("evidenceIds").add("JOB.COMPANY");
        claim.putArray("contentPaths")
                .add("/cv/coreSkills/0/name")
                .add("/cv/coreSkills/0/evidence");
        claim.put("reviewText", "");
        ((ArrayNode) output.path("claims")).add(claim);

        assertRejected(
                output,
                "atomic final claim is not an exact approved fact");
    }

    @Test
    void canonicalizesFromTheOnlyApprovedPathFactWhenCitationIsWrong()
            throws Exception {
        ObjectNode output = validOutput();
        ((ObjectNode) output.path("coverLetter"))
                .put("jobTitle", "Senior Architect");
        ArrayNode evidenceIds =
                (ArrayNode) output.at("/claims/3/evidenceIds");
        evidenceIds.removeAll().add("JOB.COMPANY");

        GeneratedApplicationDocuments corrected = parse(output);

        assertEquals(
                "Java Developer",
                corrected.getCoverLetter().getJobTitle());
        assertTrue(corrected.getClaims().get(3)
                .getEvidenceIds().contains("JOB.TITLE"));
    }

    @Test
    void clearsOptionalAtomicContentWhenNoApprovedFactExists()
            throws Exception {
        ObjectNode output = validOutput();
        addQualification(output, "BSc Computing", "2024");
        ((ObjectNode) output.at("/cv/qualifications/0"))
                .put("status", "Completed")
                .put("grade", "Distinction");
        ObjectNode qualificationClaim =
                (ObjectNode) output.at("/claims/10");
        ArrayNode evidenceIds =
                (ArrayNode) qualificationClaim.path("evidenceIds");
        for (int index = evidenceIds.size() - 1;
                index >= 0;
                index--) {
            String evidenceId = evidenceIds.get(index).asText();
            if (evidenceId.endsWith(".STATUS")
                    || evidenceId.endsWith(".GRADE")) {
                evidenceIds.remove(index);
            }
        }
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                catalog.records().stream()
                        .filter(record ->
                                !record.evidenceId().endsWith(".STATUS")
                                        && !record.evidenceId()
                                                .endsWith(".GRADE"))
                        .toList(),
                catalog.sectionOrder());

        GeneratedApplicationDocuments corrected = parse(output);

        assertEquals(
                "",
                corrected.getCv().getQualifications().get(0)
                        .getStatus());
        assertEquals(
                "",
                corrected.getCv().getQualifications().get(0)
                        .getGrade());
        assertFalse(corrected.getClaims().stream()
                .flatMap(claim -> claim.getContentPaths().stream())
                .anyMatch(path ->
                        path.equals("/cv/qualifications/0/status")
                                || path.equals(
                                    "/cv/qualifications/0/grade")));
    }

    @Test
    void clearsUnsupportedOptionalAtomicContentMissingFromTheClaimLedger()
            throws Exception {
        ObjectNode output = validOutput();
        addQualification(output, "BSc Computing", "2024");
        ObjectNode qualification =
                (ObjectNode) output.at("/cv/qualifications/0");
        qualification.put("status", "Completed");
        ObjectNode qualificationClaim =
                (ObjectNode) output.at("/claims/10");
        ArrayNode evidenceIds =
                (ArrayNode) qualificationClaim.path("evidenceIds");
        for (int index = evidenceIds.size() - 1;
                index >= 0;
                index--) {
            if (evidenceIds.get(index).asText()
                    .endsWith(".STATUS")) {
                evidenceIds.remove(index);
            }
        }
        ArrayNode contentPaths =
                (ArrayNode) qualificationClaim.path("contentPaths");
        for (int index = contentPaths.size() - 1;
                index >= 0;
                index--) {
            if (contentPaths.get(index).asText()
                    .equals("/cv/qualifications/0/status")) {
                contentPaths.remove(index);
            }
        }
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                catalog.records().stream()
                        .filter(record ->
                                !record.evidenceId()
                                        .endsWith(".STATUS"))
                        .toList(),
                catalog.sectionOrder());

        GeneratedApplicationDocuments corrected = parse(output);

        assertEquals(
                "",
                corrected.getCv().getQualifications().get(0)
                        .getStatus());
        assertFalse(corrected.getClaims().stream()
                .flatMap(claim -> claim.getContentPaths().stream())
                .anyMatch("/cv/qualifications/0/status"::equals));
    }

    @Test
    void clearsAmbiguousOptionalAtomicContentRatherThanGuessing()
            throws Exception {
        ObjectNode output = validOutput();
        addQualification(output, "BSc Computing", "2024");
        ((ObjectNode) output.at("/cv/qualifications/0"))
                .put("status", "Finished");
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                java.util.stream.Stream.concat(
                                catalog.records().stream(),
                                java.util.stream.Stream.of(
                                        new com.jobseekercopilot
                                                .cvcoverletter.model
                                                .ApprovedEvidenceRecord(
                                                        "PROFILE.QUALIFICATION.2.STATUS",
                                                        com.jobseekercopilot
                                                                .cvcoverletter.model
                                                                .EvidenceSource.PROFILE,
                                                        "/profile/qualifications/1/status",
                                                        "IN_PROGRESS")))
                        .toList(),
                catalog.sectionOrder());
        ((ArrayNode) output.at("/claims/10/evidenceIds"))
                .add("PROFILE.QUALIFICATION.2.STATUS");
        ((ArrayNode) output.at("/claims/10/contentPaths"))
                .removeAll()
                .add("/cv/qualifications");

        GeneratedApplicationDocuments corrected = parse(output);

        assertEquals(
                "",
                corrected.getCv().getQualifications().get(0)
                        .getStatus());
    }

    @Test
    void rejectsUnsupportedMotivationAvailabilitySalaryAndRightToWork() throws Exception {
        for (String unsupported : List.of(
                "I am passionate about this role.",
                "I am available immediately.",
                "My salary expectation is £70,000.",
                "I have the right to work in the UK."
        )) {
            ObjectNode output = validOutput();
            ((ArrayNode) output.at("/coverLetter/bodyParagraphs"))
                    .set(0, objectMapper.getNodeFactory().textNode(unsupported));
            InvalidLlmResponseException error = assertThrows(
                    InvalidLlmResponseException.class,
                    () -> parse(output),
                    unsupported);
            assertTrue(
                    error.getMessage().contains("absent from approved evidence"),
                    unsupported + " => " + error.getMessage());
        }
    }

    @Test
    void rejectsJobAdvertAsSoleEvidenceForCandidateSkill() throws Exception {
        ObjectNode output = validOutput();
        ((ObjectNode) output.path("cv")).put("personalSummary", "A reliable service developer.");
        ArrayNode evidenceIds = (ArrayNode) output.at("/claims/1/evidenceIds");
        evidenceIds.removeAll();
        evidenceIds.add("JOB.DESCRIPTION");

        assertRejected(output, "candidate claim has no approved profile evidence");
    }

    @Test
    void versionedEvidenceIsStableAndCannotCrossDocumentPurposes()
            throws Exception {
        catalog = new ClaimEvidenceCatalogFactory().create(
                new GenerationInputNormalizer(
                        Clock.fixed(
                                Instant.parse("2026-07-24T13:00:00Z"),
                                ZoneOffset.UTC))
                        .normalize(
                                "owner-secret",
                                validVersionedRequest()));
        ObjectNode output = versionedOutput();

        GeneratedApplicationDocuments accepted = parse(output);
        assertEquals(10, accepted.getClaims().size());

        ((ArrayNode) output.at("/claims/1/evidenceIds"))
                .set(
                        0,
                        objectMapper.getNodeFactory().textNode(
                                com.jobseekercopilot.cvcoverletter
                                        .GenerationInputFixtures
                                        .COVER_EXPERIENCE_FACT_ID
                                        .toString()));
        assertRejected(
                output,
                "not approved for this document purpose");
    }

    @Test
    void splitsModelClaimsByDocumentPurposeBeforeEvidenceValidation()
            throws Exception {
        catalog = new ClaimEvidenceCatalogFactory().create(
                new GenerationInputNormalizer(
                        Clock.fixed(
                                Instant.parse("2026-07-24T13:00:00Z"),
                                ZoneOffset.UTC))
                        .normalize(
                                "owner-secret",
                                validVersionedRequest()));
        ObjectNode output = versionedOutput();
        combineClaimPurposes(output);

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(10, accepted.getClaims().size());
        assertTrue(accepted.getClaims().stream()
                .anyMatch(claim -> "CLAIM-1000".equals(claim.getClaimId())));
        accepted.getClaims().stream()
                .filter(claim -> claim.getContentPaths() != null
                        && !claim.getContentPaths().isEmpty())
                .forEach(claim -> {
                    boolean cvOnly = claim.getContentPaths().stream()
                            .allMatch(path -> path.startsWith("/cv/"));
                    boolean coverLetterOnly = claim.getContentPaths().stream()
                            .allMatch(path -> path.startsWith("/coverLetter/"));
                    assertTrue(cvOnly || coverLetterOnly);
                    assertFalse(cvOnly && coverLetterOnly);
                });

        ObjectNode unknownEvidence = versionedOutput();
        combineClaimPurposes(unknownEvidence);
        ((ArrayNode) unknownEvidence.at("/claims/1/evidenceIds"))
                .add("UNKNOWN.EVIDENCE");
        assertRejected(unknownEvidence, "evidence ID is not approved");
    }

    @Test
    void expandsContainerPointersAndStillValidatesEveryFinalTextPath()
            throws Exception {
        ObjectNode output = validOutput();
        ArrayNode claims = (ArrayNode) output.path("claims");
        ObjectNode combinedParagraphClaim = (ObjectNode) claims.get(6);
        ObjectNode secondParagraphClaim = (ObjectNode) claims.get(7);
        secondParagraphClaim.withArray("evidenceIds")
                .forEach(combinedParagraphClaim.withArray("evidenceIds")::add);
        combinedParagraphClaim.withArray("contentPaths")
                .removeAll()
                .add("/coverLetter/bodyParagraphs");
        claims.remove(7);

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(9, accepted.getClaims().size());
        assertEquals(
                List.of(
                        "/coverLetter/bodyParagraphs/0",
                        "/coverLetter/bodyParagraphs/1"),
                accepted.getClaims().get(6).getContentPaths());
    }

    @Test
    void splitsExpandedContainerClaimsAtThePublishedReferenceLimit()
            throws Exception {
        ObjectNode output = validOutput();
        ArrayNode skills = (ArrayNode) output.at("/cv/coreSkills");
        for (int index = 0; index < 20; index++) {
            ObjectNode skill = objectMapper.createObjectNode();
            skill.put("name", "Java");
            skill.put("evidence", "Java");
            skills.add(skill);
        }
        ObjectNode containerClaim = objectMapper.createObjectNode();
        containerClaim.put("claimId", "CLAIM-020");
        containerClaim.put("disposition", "SUPPORTED");
        containerClaim.putArray("evidenceIds").add("PROFILE.SKILL.1");
        containerClaim.putArray("contentPaths").add("/cv/coreSkills");
        containerClaim.put("reviewText", "");
        ((ArrayNode) output.path("claims")).add(containerClaim);

        GeneratedApplicationDocuments accepted = parse(output);

        List<String> skillPaths = accepted.getClaims().stream()
                .flatMap(claim -> claim.getContentPaths().stream())
                .filter(path -> path.startsWith("/cv/coreSkills/"))
                .toList();
        assertEquals(40, skillPaths.size());
        assertTrue(accepted.getClaims().stream()
                .allMatch(claim ->
                        claim.getContentPaths().size() <= 30));
        assertTrue(accepted.getClaims().stream()
                .allMatch(claim ->
                        claim.getEvidenceIds().size() <= 30));
    }

    @Test
    void assignsAStableInternalIdWhenTheModelDuplicatesAClaimId()
            throws Exception {
        ObjectNode output = validOutput();
        ((ObjectNode) output.at("/claims/1")).put("claimId", "CLAIM-001");

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals("CLAIM-001", accepted.getClaims().get(0).getClaimId());
        assertEquals("CLAIM-1000", accepted.getClaims().get(1).getClaimId());
    }

    @Test
    void restoresAnExactAtomicEvidenceReferenceAndCanonicalizesFinalContent()
            throws Exception {
        ObjectNode output = validOutput();
        ArrayNode evidenceIds =
                (ArrayNode) output.at("/claims/0/evidenceIds");
        evidenceIds.removeAll().add("PROFILE.SKILL.1");

        GeneratedApplicationDocuments accepted = parse(output);

        assertTrue(accepted.getClaims().get(0).getEvidenceIds()
                .contains("JOB.TITLE"));

        ((ObjectNode) output.path("cv")).put(
                "targetRole",
                "Fabricated Architect");
        evidenceIds.removeAll().add("JOB.TITLE");
        GeneratedApplicationDocuments corrected = parse(output);
        assertEquals(
                "Java Developer",
                corrected.getCv().getTargetRole());
    }

    @Test
    void restoresOnlyCandidateEvidenceForAnExactSpecificNarrativeTerm()
            throws Exception {
        ObjectNode output = validOutput();
        ((ObjectNode) output.path("coverLetter")).withArray("bodyParagraphs")
                .set(1, objectMapper.getNodeFactory().textNode(
                        "I build useful Java services."));
        ArrayNode evidenceIds =
                (ArrayNode) output.at("/claims/7/evidenceIds");
        evidenceIds.removeAll().add("JOB.DESCRIPTION");

        GeneratedApplicationDocuments accepted = parse(output);

        assertTrue(accepted.getClaims().get(7).getEvidenceIds()
                .contains("PROFILE.SKILL.1"));

        ((ObjectNode) output.path("coverLetter")).withArray("bodyParagraphs")
                .set(1, objectMapper.getNodeFactory().textNode(
                        "I build useful Kubernetes services."));
        assertRejected(
                output,
                "sensitive or specific claim is absent from approved evidence");
    }

    @Test
    void replacesModelAuthoredSkillEvidenceWithExactClaimantEvidence()
            throws Exception {
        ObjectNode output = validOutput();
        ObjectNode skill = objectMapper.createObjectNode();
        skill.put("name", "Java");
        skill.put("evidence", "More than 99 years of Java experience.");
        ((ArrayNode) output.at("/cv/coreSkills")).add(skill);

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                "Java",
                accepted.getCv().getCoreSkills().get(0).getEvidence());
        assertTrue(accepted.getClaims().stream()
                .anyMatch(claim -> claim.getContentPaths().equals(
                        List.of("/cv/coreSkills/0/name"))));
        assertTrue(accepted.getClaims().stream()
                .anyMatch(claim -> claim.getContentPaths().equals(
                        List.of("/cv/coreSkills/0/evidence"))));
    }

    @Test
    void removesWorkHistoryThatInventsRequiredAtomicDates()
            throws Exception {
        ObjectNode output = validOutput();
        ObjectNode history = objectMapper.createObjectNode();
        history.put("jobTitle", "Developer");
        history.put("employer", "Example employer");
        history.put("startDate", "1900");
        history.put("endDate", "");
        history.putArray("responsibilities");
        history.put("tailoredDescription", "Unsupported history.");
        ((ArrayNode) output.at("/cv/workHistory")).add(history);

        GeneratedApplicationDocuments accepted = parse(output);

        assertTrue(accepted.getCv().getWorkHistory().isEmpty());
    }

    private void assertUnsafePersonalSummary(String value, String reason) throws Exception {
        ObjectNode output = validOutput();
        ((ObjectNode) output.path("cv")).put("personalSummary", value);
        assertRejected(output, reason);
    }

    private InvalidLlmResponseException assertRejected(ObjectNode output, String reason) {
        InvalidLlmResponseException error = assertThrows(
                InvalidLlmResponseException.class,
                () -> parse(output));
        assertTrue(error.getMessage().contains(reason), error.getMessage());
        return error;
    }

    private GeneratedApplicationDocuments parse(ObjectNode output) throws Exception {
        return parser.parse(objectMapper.writeValueAsString(output), schema, catalog);
    }

    private ObjectNode validOutput() throws Exception {
        return (ObjectNode) objectMapper.readTree(CvCoverLetterServiceTest.validJson());
    }

    private ObjectNode versionedOutput() throws Exception {
        ObjectNode output = validOutput();
        String cvFact = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.CV_SKILL_FACT_ID.toString();
        String coverFact = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.COVER_EXPERIENCE_FACT_ID
                .toString();
        ((ArrayNode) output.at("/claims/1/evidenceIds"))
                .set(
                        0,
                        objectMapper.getNodeFactory().textNode(cvFact));
        for (int claimIndex : List.of(5, 6, 7, 8)) {
            ArrayNode evidence =
                    (ArrayNode) output.at(
                            "/claims/"
                                    + claimIndex
                                    + "/evidenceIds");
            evidence.removeAll();
            evidence.add(coverFact);
        }
        ArrayNode cvTitleEvidence =
                (ArrayNode) output.at("/claims/9/evidenceIds");
        cvTitleEvidence.removeAll();
        cvTitleEvidence.add("JOB.TITLE");
        return output;
    }

    private void combineClaimPurposes(ObjectNode output) {
        ArrayNode claims = (ArrayNode) output.path("claims");
        ObjectNode cvClaim = (ObjectNode) claims.get(1);
        ObjectNode coverLetterClaim = (ObjectNode) claims.get(6);
        coverLetterClaim.withArray("evidenceIds")
                .forEach(cvClaim.withArray("evidenceIds")::add);
        coverLetterClaim.withArray("contentPaths")
                .forEach(cvClaim.withArray("contentPaths")::add);
        claims.remove(6);
    }

    private ObjectNode reviewOnlyClaim(String id, String disposition, String reviewText) {
        ObjectNode claim = objectMapper.createObjectNode();
        claim.put("claimId", id);
        claim.put("disposition", disposition);
        claim.putArray("evidenceIds");
        claim.putArray("contentPaths");
        claim.put("reviewText", reviewText);
        return claim;
    }

    private void addQualification(ObjectNode output, String name, String date) {
        ObjectNode qualification = objectMapper.createObjectNode();
        qualification.put("qualificationName", name);
        qualification.put("issuingBody", "Example University");
        qualification.put("status", "COMPLETED");
        qualification.put("grade", "First");
        qualification.put("dateAchieved", date);
        qualification.put("expectedCompletion", "");
        ((ArrayNode) output.at("/cv/qualifications")).add(qualification);

        ObjectNode claim = objectMapper.createObjectNode();
        claim.put("claimId", "CLAIM-011");
        claim.put("disposition", "SUPPORTED");
        claim.putArray("evidenceIds")
                .add("PROFILE.QUALIFICATION.1.NAME")
                .add("PROFILE.QUALIFICATION.1.ISSUING_BODY")
                .add("PROFILE.QUALIFICATION.1.STATUS")
                .add("PROFILE.QUALIFICATION.1.GRADE")
                .add("PROFILE.QUALIFICATION.1.DATE_ACHIEVED");
        claim.putArray("contentPaths")
                .add("/cv/qualifications/0/qualificationName")
                .add("/cv/qualifications/0/issuingBody")
                .add("/cv/qualifications/0/status")
                .add("/cv/qualifications/0/grade")
                .add("/cv/qualifications/0/dateAchieved");
        claim.put("reviewText", "");
        ((ArrayNode) output.path("claims")).add(claim);
    }
}
