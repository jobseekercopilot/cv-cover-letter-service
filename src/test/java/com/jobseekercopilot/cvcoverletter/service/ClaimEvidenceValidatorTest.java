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
import com.jobseekercopilot.cvcoverletter.dto.ClaimDisposition;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.model.ApprovedEvidenceRecord;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import com.jobseekercopilot.cvcoverletter.model.EvidencePurpose;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
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
        parser = new LlmResponseParser(
                objectMapper,
                new ClaimEvidenceValidator(),
                new GeneratedDocumentQualityValidator());
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.5.1/output-schema.json")) {
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
        assertRejected(
                missingCoverage,
                "final content contains unaccounted claim paths");

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
    void rejectsTheExactRealProviderLedgerOmissionWithoutWeakeningCoverage()
            throws Exception {
        ObjectNode output = validOutput();
        ArrayNode claims = (ArrayNode) output.path("claims");
        java.util.Set<String> omittedPaths = java.util.Set.of(
                "/cv/title",
                "/cv/personalSummary",
                "/coverLetter/title",
                "/coverLetter/bodyParagraphs/1",
                "/coverLetter/bodyParagraphs/2",
                "/coverLetter/closingParagraph");
        for (int index = claims.size() - 1; index >= 0; index--) {
            JsonNode contentPaths = claims.get(index).path("contentPaths");
            boolean coversOmittedPath = false;
            for (JsonNode contentPath : contentPaths) {
                if (omittedPaths.contains(contentPath.asText())) {
                    coversOmittedPath = true;
                    break;
                }
            }
            if (coversOmittedPath) {
                claims.remove(index);
            }
        }

        InvalidLlmResponseException error =
                assertRejected(output, "final content contains unaccounted claim paths");

        omittedPaths.forEach(path ->
                assertTrue(error.getMessage().contains(path), error.getMessage()));
    }

    @Test
    void groundsAnUnclaimedProjectDescriptionInItsExactSelectedFact()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        removeContentPath(output, 1, "/cv/projects/0/description");
        ((ObjectNode) output.at("/cv/projects/0"))
                .put(
                        "description",
                        "This model-authored paraphrase has no claim.");

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                "Built useful services.",
                accepted.getCv().getProjects().get(0).getDescription());
        var descriptionClaim = accepted.getClaims().stream()
                .filter(claim -> claim.getContentPaths().contains(
                        "/cv/projects/0/description"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                List.of(com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .CV_PROJECT_FACT_ID.toString()),
                descriptionClaim.getEvidenceIds());
        assertEquals(
                ClaimDisposition.SUPPORTED,
                descriptionClaim.getDisposition());
        assertEquals(
                List.of("/cv/projects/0/description"),
                descriptionClaim.getContentPaths());
        assertTrue(
                descriptionClaim.getClaimId().startsWith("CLAIM-2"));
    }

    @Test
    void stillRejectsAnUnclaimedRequiredNarrativePath()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        removeContentPath(output, 1, "/cv/personalSummary");

        InvalidLlmResponseException error = assertRejected(
                output,
                "final content contains unaccounted claim paths");

        assertTrue(
                error.getMessage().contains("/cv/personalSummary"),
                error.getMessage());
    }

    @Test
    void replacesUnclaimedApplicationBookendsWithCanonicalGroundedText()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        removeClaimsCovering(
                output,
                java.util.Set.of(
                        "/coverLetter/openingParagraph",
                        "/coverLetter/closingParagraph"));
        ((ObjectNode) output.at("/coverLetter"))
                .put(
                        "openingParagraph",
                        "Model prose without ledger coverage.")
                .put(
                        "closingParagraph",
                        "Another unaccounted model sentence.");

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                "Please consider my application for this role.",
                accepted.getCoverLetter().getOpeningParagraph());
        assertEquals(
                "Thank you for considering my application.",
                accepted.getCoverLetter().getClosingParagraph());
        for (String path : List.of(
                "/coverLetter/openingParagraph",
                "/coverLetter/closingParagraph")) {
            var claim = accepted.getClaims().stream()
                    .filter(candidate ->
                            candidate.getContentPaths().contains(path))
                    .findFirst()
                    .orElseThrow();
            assertEquals(
                    ClaimDisposition.SUPPORTED,
                    claim.getDisposition());
            assertEquals(
                    List.of(
                            "REQUEST.GENERATION_INTENT",
                            "JOB.TITLE",
                            "JOB.COMPANY"),
                    claim.getEvidenceIds());
            assertEquals(List.of(path), claim.getContentPaths());
            assertTrue(
                    claim.getClaimId().startsWith("CLAIM-2"));
        }
    }

    @Test
    void canonicalBookendExceptionRequiresExactTextAndAllCanonicalEvidence()
            throws Exception {
        useVersionedCatalog();
        ObjectNode exact = versionedOutput();
        ((ObjectNode) exact.at("/coverLetter"))
                .put(
                        "openingParagraph",
                        "Please consider my application for this role.");
        ArrayNode exactEvidence =
                (ArrayNode) exact.at("/claims/5/evidenceIds");
        exactEvidence.removeAll()
                .add("REQUEST.GENERATION_INTENT")
                .add("JOB.TITLE")
                .add("JOB.COMPANY");

        GeneratedApplicationDocuments accepted = parse(exact);

        assertEquals(
                "Please consider my application for this role.",
                accepted.getCoverLetter().getOpeningParagraph());

        for (String changed : List.of(
                "I am ideally suited to Java Developer at Example Ltd.",
                "please consider my application for this role.",
                "Please  consider my application for this role.",
                "Please consider my application for this role. ")) {
            ObjectNode changedText = versionedOutput();
            ((ObjectNode) changedText.at("/coverLetter"))
                    .put("openingParagraph", changed);
            ArrayNode changedTextEvidence =
                    (ArrayNode) changedText.at(
                            "/claims/5/evidenceIds");
            changedTextEvidence.removeAll()
                    .add("REQUEST.GENERATION_INTENT")
                    .add("JOB.TITLE")
                    .add("JOB.COMPANY");
            assertRejected(
                    changedText,
                    "candidate claim has no confirmed claimant evidence");
        }

        ObjectNode missingEvidence = versionedOutput();
        ((ObjectNode) missingEvidence.at("/coverLetter"))
                .put(
                        "openingParagraph",
                        "Please consider my application for this role.");
        ArrayNode incompleteEvidence =
                (ArrayNode) missingEvidence.at("/claims/5/evidenceIds");
        incompleteEvidence.removeAll()
                .add("JOB.TITLE")
                .add("JOB.COMPANY");
        assertRejected(
                missingEvidence,
                "candidate claim has no confirmed claimant evidence");

        ObjectNode surplusJobEvidence = versionedOutput();
        ((ObjectNode) surplusJobEvidence.at("/coverLetter"))
                .put(
                        "openingParagraph",
                        "Please consider my application for this role.");
        ArrayNode surplusEvidence =
                (ArrayNode) surplusJobEvidence.at(
                        "/claims/5/evidenceIds");
        surplusEvidence.removeAll()
                .add("REQUEST.GENERATION_INTENT")
                .add("JOB.TITLE")
                .add("JOB.COMPANY")
                .add("JOB.DESCRIPTION");
        assertRejected(
                surplusJobEvidence,
                "candidate claim has no confirmed claimant evidence");

        ObjectNode claimantEvidence = versionedOutput();
        ((ObjectNode) claimantEvidence.at("/coverLetter"))
                .put(
                        "openingParagraph",
                        "Please consider my application for this role.");
        ArrayNode ordinaryEvidence =
                (ArrayNode) claimantEvidence.at(
                        "/claims/5/evidenceIds");
        ordinaryEvidence.removeAll()
                .add("REQUEST.GENERATION_INTENT")
                .add("JOB.TITLE")
                .add("JOB.COMPANY")
                .add(com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .COVER_EXPERIENCE_FACT_ID.toString());

        GeneratedApplicationDocuments ordinarilyGrounded =
                parse(claimantEvidence);

        assertEquals(
                "Please consider my application for this role.",
                ordinarilyGrounded.getCoverLetter()
                        .getOpeningParagraph());
    }

    @Test
    void fixedTextDoesNotChangeLegacyEvidenceHandling()
            throws Exception {
        ObjectNode output = validOutput();
        ((ObjectNode) output.at("/coverLetter"))
                .put(
                        "closingParagraph",
                        "Thank you for considering my application.");

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                "Thank you for considering my application.",
                accepted.getCoverLetter().getClosingParagraph());
        assertEquals(
                List.of("JOB.DESCRIPTION"),
                accepted.getClaims().stream()
                        .filter(claim -> claim.getContentPaths()
                                .contains(
                                        "/coverLetter/closingParagraph"))
                        .findFirst()
                        .orElseThrow()
                        .getEvidenceIds());
    }

    @Test
    void missingCanonicalIntentLeavesAnOmittedBookendRejected()
            throws Exception {
        useVersionedCatalog();
        catalog = withoutEvidence("REQUEST.GENERATION_INTENT");
        ObjectNode output = versionedOutput();
        removeClaimsCovering(
                output,
                java.util.Set.of(
                        "/coverLetter/openingParagraph"));

        InvalidLlmResponseException error = assertRejected(
                output,
                "final content contains unaccounted claim paths");

        assertTrue(
                error.getMessage().contains(
                        "/coverLetter/openingParagraph"),
                error.getMessage());
    }

    @Test
    void recoveringOneBookendNeverChangesTheOtherClaimedBookend()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        removeClaimsCovering(
                output,
                java.util.Set.of(
                        "/coverLetter/openingParagraph"));
        ((ObjectNode) output.at("/coverLetter"))
                .put(
                        "openingParagraph",
                        "Unclaimed model prose.")
                .put(
                        "closingParagraph",
                        "A claimed closing remains unchanged.");

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                "Please consider my application for this role.",
                accepted.getCoverLetter().getOpeningParagraph());
        assertEquals(
                "A claimed closing remains unchanged.",
                accepted.getCoverLetter().getClosingParagraph());
    }

    @Test
    void neverInterpolatesUntrustedJobIdentityIntoRecoveredBookends()
            throws Exception {
        useVersionedCatalog();
        replaceEvidenceValue(
                "JOB.TITLE",
                "Developer. I manage budgets");
        replaceEvidenceValue(
                "JOB.COMPANY",
                "Example Ltd. I lead teams");
        ObjectNode output = versionedOutput();
        removeClaimsCovering(
                output,
                java.util.Set.of(
                        "/coverLetter/openingParagraph",
                        "/coverLetter/closingParagraph"));

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                "Developer. I manage budgets",
                accepted.getCoverLetter().getJobTitle());
        assertEquals(
                "Example Ltd. I lead teams",
                accepted.getCoverLetter().getCompanyName());
        assertEquals(
                "Please consider my application for this role.",
                accepted.getCoverLetter().getOpeningParagraph());
        assertEquals(
                "Thank you for considering my application.",
                accepted.getCoverLetter().getClosingParagraph());
        assertFalse(accepted.getCoverLetter()
                .getOpeningParagraph().contains("I manage budgets"));
        assertFalse(accepted.getCoverLetter()
                .getOpeningParagraph().contains("I lead teams"));
        assertFalse(accepted.getCoverLetter()
                .getClosingParagraph().contains("I manage budgets"));
        assertFalse(accepted.getCoverLetter()
                .getClosingParagraph().contains("I lead teams"));
    }

    @Test
    void neverReplacesClaimedUnsafeApplicationProse()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ((ObjectNode) output.at("/coverLetter"))
                .put(
                        "openingParagraph",
                        "I am a passionate Kubernetes expert.");

        assertRejected(
                output,
                "sensitive or specific claim is absent from approved evidence");
    }

    @Test
    void leavesAClaimedGroundedProjectDescriptionUnchanged()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ((ObjectNode) output.at("/cv/projects/0"))
                .put(
                        "description",
                        "Developed useful services.");

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                "Developed useful services.",
                accepted.getCv().getProjects().get(0).getDescription());
        assertTrue(accepted.getClaims().stream()
                .flatMap(claim -> claim.getContentPaths().stream())
                .anyMatch("/cv/projects/0/description"::equals));
    }

    @Test
    void claimedUnsupportedProjectTextStillFailsEvidenceValidation()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ((ObjectNode) output.at("/cv/projects/0"))
                .put("description", "Built Kubernetes services.");

        assertRejected(
                output,
                "sensitive or specific claim is absent from approved evidence");
    }

    @Test
    void claimedGenericProjectProseCannotUseOnlyHeadingEvidence()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ((ObjectNode) output.at("/cv/projects/0"))
                .put(
                        "description",
                        "Delivered useful services with a careful approach.");
        removeEvidenceId(
                output,
                1,
                com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .CV_PROJECT_FACT_ID.toString());

        assertRejected(
                output,
                "project narrative lacks same-selection evidence");
    }

    @Test
    void isolatesProjectClaimsFromAGroupedQualificationSelection()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        String qualificationFactId =
                "81000000-0000-4000-8000-000000000001";
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        records.add(new ApprovedEvidenceRecord(
                qualificationFactId,
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/cv/selections/1/facts/0",
                "Level 3 Software Development",
                "QUALIFICATION_TITLE",
                "QUALIFICATION_TRAINING",
                EvidencePurpose.CV));
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());

        ObjectNode qualification = objectMapper.createObjectNode();
        qualification.put(
                "qualificationName",
                "Level 3 Software Development");
        qualification.put("issuingBody", "");
        qualification.put("status", "");
        qualification.put("grade", "");
        qualification.put("dateAchieved", "");
        qualification.put("expectedCompletion", "");
        ((ArrayNode) output.at("/cv/qualifications"))
                .add(qualification);
        ((ArrayNode) output.at("/claims/1/evidenceIds"))
                .add(qualificationFactId);
        ((ArrayNode) output.at("/claims/1/contentPaths"))
                .add("/cv/qualifications/0/qualificationName");

        GeneratedApplicationDocuments accepted = parse(output);

        var projectClaims = accepted.getClaims().stream()
                .filter(claim ->
                        claim.getContentPaths().stream()
                                .anyMatch(path ->
                                        path.startsWith(
                                                "/cv/projects/0/")))
                .toList();
        assertFalse(projectClaims.isEmpty());
        assertTrue(projectClaims.stream()
                .noneMatch(claim ->
                        claim.getEvidenceIds()
                                .contains(qualificationFactId)));
        assertTrue(accepted.getClaims().stream()
                .filter(claim ->
                        claim.getContentPaths().contains(
                                "/cv/qualifications/0/qualificationName"))
                .anyMatch(claim ->
                        claim.getEvidenceIds()
                                .contains(qualificationFactId)));
    }

    @Test
    void splitsTwoSelectedProjectsIntoExactOnceProjectClaims()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        String secondTitleFactId =
                "81100000-0000-4000-8000-000000000001";
        String secondDescriptionFactId =
                "81100000-0000-4000-8000-000000000002";
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        records.add(new ApprovedEvidenceRecord(
                secondTitleFactId,
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/cv/selections/1/facts/0",
                "Evidence Library",
                "HEADING",
                "PROJECT",
                EvidencePurpose.CV));
        records.add(new ApprovedEvidenceRecord(
                secondDescriptionFactId,
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/cv/selections/1/facts/1",
                "Built a versioned evidence library.",
                "DESCRIPTION",
                "PROJECT",
                EvidencePurpose.CV));
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());

        ObjectNode secondProject = objectMapper.createObjectNode();
        secondProject.put("title", "Evidence Library");
        secondProject.put("role", "");
        secondProject.put("context", "");
        secondProject.put("startDate", "");
        secondProject.put("endDate", "");
        secondProject.put(
                "description",
                "Built a versioned evidence library.");
        secondProject.putArray("highlights");
        ((ArrayNode) output.at("/cv/projects"))
                .add(secondProject);
        ((ArrayNode) output.at("/claims/1/evidenceIds"))
                .add(secondTitleFactId)
                .add(secondDescriptionFactId);
        ((ArrayNode) output.at("/claims/1/contentPaths"))
                .add("/cv/projects/1/title")
                .add("/cv/projects/1/description");

        GeneratedApplicationDocuments accepted = parse(output);

        for (String path : List.of(
                "/cv/projects/0/title",
                "/cv/projects/0/description",
                "/cv/projects/1/title",
                "/cv/projects/1/description")) {
            assertEquals(
                    1,
                    accepted.getClaims().stream()
                            .flatMap(claim ->
                                    claim.getContentPaths().stream())
                            .filter(path::equals)
                            .count());
        }
        assertEquals(
                accepted.getClaims().size(),
                accepted.getClaims().stream()
                        .map(claim -> claim.getClaimId())
                        .distinct()
                        .count());
        accepted.getClaims().stream()
                .filter(claim ->
                        claim.getContentPaths().stream()
                                .anyMatch(path ->
                                        path.startsWith(
                                                "/cv/projects/0/")))
                .forEach(claim -> {
                    assertFalse(claim.getEvidenceIds()
                            .contains(secondTitleFactId));
                    assertFalse(claim.getEvidenceIds()
                            .contains(secondDescriptionFactId));
                });
    }

    @Test
    void rejectsSpecificTermEvidenceLaunderedFromAnotherProject()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        String otherProjectFactId =
                "81200000-0000-4000-8000-000000000001";
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        records.add(new ApprovedEvidenceRecord(
                otherProjectFactId,
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/cv/selections/1/facts/0",
                "Kubernetes",
                "DESCRIPTION",
                "PROJECT",
                EvidencePurpose.CV));
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());
        ((ObjectNode) output.at("/cv/projects/0"))
                .put(
                        "description",
                        "Built Kubernetes services.");
        ((ArrayNode) output.at("/claims/1/evidenceIds"))
                .add(otherProjectFactId);

        assertRejected(
                output,
                "sensitive or specific claim is absent from approved evidence");
    }

    @Test
    void leavesAmbiguousProjectTitleSelectionsToFailClosedValidation()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        String ambiguousTitleFactId =
                "82000000-0000-4000-8000-000000000001";
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        records.add(new ApprovedEvidenceRecord(
                ambiguousTitleFactId,
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/cv/selections/1/facts/0",
                "Job Seeker Copilot",
                "HEADING",
                "PROJECT",
                EvidencePurpose.CV));
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());
        ((ArrayNode) output.at("/claims/1/evidenceIds"))
                .add(ambiguousTitleFactId);

        assertRejected(
                output,
                "project fields must come from one selected project entry");
    }

    @Test
    void projectIsolationNeverHidesAnUnknownEvidenceId()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ((ArrayNode) output.at("/claims/1/evidenceIds"))
                .add("83000000-0000-4000-8000-000000000099");

        assertRejected(output, "evidence ID is not approved");
    }

    @Test
    void rejectsAnOverflowingProjectIndexAsAnUnapprovedContentPath()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ((ArrayNode) output.at("/claims/1/contentPaths"))
                .add("/cv/projects/9999999999/title");

        assertRejected(
                output,
                "content path is not an approved final claim path");
    }

    @Test
    void rejectsAnUnclaimedProjectDescriptionWhenItsSelectionHasNoDescriptionFact()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        removeContentPath(output, 1, "/cv/projects/0/description");
        removeEvidenceId(
                output,
                1,
                com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .CV_PROJECT_FACT_ID.toString());
        catalog = withoutEvidence(
                com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .CV_PROJECT_FACT_ID.toString());

        InvalidLlmResponseException error = assertRejected(
                output,
                "final content contains unaccounted claim paths");

        assertTrue(
                error.getMessage().contains(
                        "/cv/projects/0/description"),
                error.getMessage());
    }

    @Test
    void rejectsAnUnclaimedProjectDescriptionWhenItsSelectionIsAmbiguous()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        removeContentPath(output, 1, "/cv/projects/0/description");
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        records.add(new ApprovedEvidenceRecord(
                "83000000-0000-4000-8000-000000000001",
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/cv/selections/0/facts/3",
                "A second description in the same project.",
                "DESCRIPTION",
                "PROJECT",
                EvidencePurpose.CV));
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());

        InvalidLlmResponseException error = assertRejected(
                output,
                "final content contains unaccounted claim paths");

        assertTrue(
                error.getMessage().contains(
                        "/cv/projects/0/description"),
                error.getMessage());
    }

    @Test
    void neverUsesDescriptionEvidenceFromAnotherProjectSelection()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        removeContentPath(output, 1, "/cv/projects/0/description");
        removeEvidenceId(
                output,
                1,
                com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .CV_PROJECT_FACT_ID.toString());
        catalog = withoutEvidence(
                com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .CV_PROJECT_FACT_ID.toString());
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        records.add(new ApprovedEvidenceRecord(
                "84000000-0000-4000-8000-000000000001",
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/cv/selections/1/facts/0",
                "Description from another selected project.",
                "DESCRIPTION",
                "PROJECT",
                EvidencePurpose.CV));
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());

        InvalidLlmResponseException error = assertRejected(
                output,
                "final content contains unaccounted claim paths");

        assertTrue(
                error.getMessage().contains(
                        "/cv/projects/0/description"),
                error.getMessage());
    }

    @Test
    void canonicalizationCannotBypassSelectedEvidenceQuality()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        removeContentPath(output, 1, "/cv/projects/0/description");
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        records.add(new ApprovedEvidenceRecord(
                "85000000-0000-4000-8000-000000000001",
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/cv/selections/1/facts/0",
                "Another selected project remains unrepresented.",
                "DESCRIPTION",
                "PROJECT",
                EvidencePurpose.CV));
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());

        assertRejected(
                output,
                "a selected evidence entry is missing from final content");
    }

    @Test
    void revalidatesSchemaAndPlainTextAfterProjectCanonicalization()
            throws Exception {
        useVersionedCatalog();
        ObjectNode oversized = versionedOutput();
        removeContentPath(
                oversized,
                1,
                "/cv/projects/0/description");
        replaceEvidenceValue(
                com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .CV_PROJECT_FACT_ID.toString(),
                "x".repeat(2_001));
        assertRejected(
                oversized,
                "does not satisfy the bounded text policy");

        useVersionedCatalog();
        ObjectNode activeContent = versionedOutput();
        removeContentPath(
                activeContent,
                1,
                "/cv/projects/0/description");
        replaceEvidenceValue(
                com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .CV_PROJECT_FACT_ID.toString(),
                "<script>alert(1)</script>");
        assertRejected(
                activeContent,
                "active or markup content is forbidden");
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
    void rejectsJobAdvertAsSoleEvidenceForCoveredVersionedPersonalContent()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ((ObjectNode) output.path("cv")).put(
                "personalSummary",
                "A reliable service developer.");
        ObjectNode personalClaim = (ObjectNode) output.at("/claims/1");
        personalClaim.withArray("evidenceIds")
                .removeAll()
                .add("JOB.DESCRIPTION");
        personalClaim.withArray("contentPaths")
                .removeAll()
                .add("/cv/personalSummary");

        assertRejected(
                output,
                "candidate claim has no approved profile evidence");
    }

    @Test
    void rejectsJobAdvertAsSoleEvidenceForCoveredVersionedCoverNarrative()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ((ArrayNode) output.at("/coverLetter/bodyParagraphs"))
                .set(
                        0,
                        objectMapper.getNodeFactory().textNode(
                                "I would bring a reliable approach."));
        ObjectNode narrativeClaim = (ObjectNode) output.at("/claims/6");
        narrativeClaim.withArray("evidenceIds")
                .removeAll()
                .add("JOB.DESCRIPTION");
        narrativeClaim.withArray("contentPaths")
                .removeAll()
                .add("/coverLetter/bodyParagraphs/0");

        assertRejected(
                output,
                "candidate claim has no confirmed claimant evidence");
    }

    @Test
    void rejectsRepeatedSkillEvidenceAndNormalisedCoverParagraphs()
            throws Exception {
        ObjectNode repeatedSkills = validOutput();
        ArrayNode skills = (ArrayNode) repeatedSkills.at("/cv/coreSkills");
        for (String name : List.of("Java", "Spring")) {
            ObjectNode skill = objectMapper.createObjectNode();
            skill.put("name", name);
            skill.put("evidence", "Built and maintained Java services.");
            skills.add(skill);
        }
        ObjectNode skillClaim = objectMapper.createObjectNode();
        skillClaim.put("claimId", "CLAIM-011");
        skillClaim.put("disposition", "SUPPORTED");
        skillClaim.putArray("evidenceIds")
                .add("PROFILE.SKILL.1")
                .add("PROFILE.SKILL.2")
                .add("PROFILE.EMPLOYMENT.1.RESPONSIBILITIES");
        skillClaim.putArray("contentPaths")
                .add("/cv/coreSkills/0/name")
                .add("/cv/coreSkills/0/evidence")
                .add("/cv/coreSkills/1/name")
                .add("/cv/coreSkills/1/evidence");
        skillClaim.put("reviewText", "");
        ((ArrayNode) repeatedSkills.path("claims")).add(skillClaim);
        assertRejected(repeatedSkills, "repeated skill evidence");

        ObjectNode repeatedParagraph = validOutput();
        ((ArrayNode) repeatedParagraph.at("/coverLetter/bodyParagraphs"))
                .set(
                        1,
                        objectMapper.getNodeFactory().textNode(
                                "My experience is a strong match."));
        assertRejected(
                repeatedParagraph,
                "duplicate normalised line or paragraph");
    }

    @Test
    void rejectsSelectedProjectEvidenceThatIsNotInTheProjectSection()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ((ArrayNode) output.at("/cv/projects")).removeAll();
        ArrayNode contentPaths =
                (ArrayNode) output.at("/claims/1/contentPaths");
        for (int index = contentPaths.size() - 1;
                index >= 0;
                index--) {
            if (contentPaths.get(index).asText()
                    .startsWith("/cv/projects/")) {
                contentPaths.remove(index);
            }
        }

        assertRejected(
                output,
                "selected evidence is not represented in its governed section");
    }

    @Test
    void requiresEightUniqueSkillsWhenEightConfirmedSkillsAreAvailable()
            throws Exception {
        useVersionedCatalog();
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        List<String> additionalSkills = List.of(
                "Kotlin",
                "TypeScript",
                "SQL",
                "Docker",
                "Terraform",
                "Angular",
                "Python");
        for (int index = 0; index < additionalSkills.size(); index++) {
            records.add(new ApprovedEvidenceRecord(
                    "81000000-0000-4000-8000-00000000000" + index,
                    EvidenceSource.EVIDENCE_SNAPSHOT,
                    "/evidenceSnapshots/cv/selections/0/facts/"
                            + (index + 3),
                    additionalSkills.get(index),
                    "DEMONSTRATED_SKILL",
                    "PROJECT",
                    EvidencePurpose.CV));
        }
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());

        assertRejected(
                versionedOutput(),
                "does not cover the available confirmed skills");
    }

    @Test
    void rejectsASelectedEvidenceEntryMissingFromFinalContent()
            throws Exception {
        useVersionedCatalog();
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        records.add(new ApprovedEvidenceRecord(
                "82000000-0000-4000-8000-000000000001",
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/coverLetter/selections/1/facts/0",
                "Led community workshops.",
                "DESCRIPTION",
                "VOLUNTEERING",
                EvidencePurpose.COVER_LETTER));
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());

        assertRejected(
                versionedOutput(),
                "a selected evidence entry is missing from final content");
    }

    @Test
    void cvClaimWithSharedStableIdDoesNotCoverACoverLetterSelection()
            throws Exception {
        useVersionedCatalog();
        String sharedId = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.CV_PROJECT_FACT_ID.toString();
        addCoverSelectionSharingEvidenceId(sharedId);

        assertRejected(
                versionedOutput(),
                "a selected evidence entry is missing from final content");
    }

    @Test
    void separatePurposeClaimsWithSharedStableIdCoverBothSelections()
            throws Exception {
        useVersionedCatalog();
        String sharedId = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.CV_PROJECT_FACT_ID.toString();
        addCoverSelectionSharingEvidenceId(sharedId);
        ObjectNode output = versionedOutput();
        ((ArrayNode) output.at("/claims/6/evidenceIds"))
                .add(sharedId);

        GeneratedApplicationDocuments accepted = parse(output);

        assertTrue(accepted.getClaims().stream()
                .filter(claim -> claim.getEvidenceIds()
                        .contains(sharedId))
                .anyMatch(claim -> claim.getContentPaths().stream()
                        .allMatch(path -> path.startsWith("/cv/"))));
        assertTrue(accepted.getClaims().stream()
                .filter(claim -> claim.getEvidenceIds()
                        .contains(sharedId))
                .anyMatch(claim -> claim.getContentPaths().stream()
                        .allMatch(path ->
                                path.startsWith("/coverLetter/"))));
    }

    @Test
    void rejectsAQualificationPhraseRepeatedInsideLongerCoverParagraphs()
            throws Exception {
        useVersionedCatalog();
        String qualificationId =
                "80000000-0000-4000-8000-000000000005";
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        records.add(new ApprovedEvidenceRecord(
                qualificationId,
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/coverLetter/selections/1/facts/0",
                "BMus Music Performance / Composition",
                "PROGRAMME_OR_SUBJECT",
                "EDUCATION",
                EvidencePurpose.COVER_LETTER));
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());
        ObjectNode output = versionedOutput();
        ArrayNode body =
                (ArrayNode) output.at("/coverLetter/bodyParagraphs");
        body.set(
                0,
                objectMapper.getNodeFactory().textNode(
                        "My BMus Music Performance / Composition developed careful delivery."));
        body.set(
                1,
                objectMapper.getNodeFactory().textNode(
                        "Through BMus Music Performance / Composition, I built useful services."));
        ((ArrayNode) output.at("/claims/6/evidenceIds"))
                .add(qualificationId);
        ((ArrayNode) output.at("/claims/7/evidenceIds"))
                .add(qualificationId);

        assertRejected(output, "qualification evidence is repeated");
    }

    @Test
    void projectOnlyEvidenceRendersAsAProjectAndNeverAsEmployment()
            throws Exception {
        useVersionedCatalog();

        GeneratedApplicationDocuments accepted = parse(versionedOutput());
        String cv = new CvDocumentRenderer().render(accepted.getCv());
        String coverLetter = new CoverLetterDocumentRenderer().render(
                accepted.getCoverLetter());

        assertTrue(cv.contains("Technical Profile"));
        assertTrue(cv.contains("Projects\nJob Seeker Copilot"));
        assertTrue(cv.contains("Technical Skills\nJava"));
        assertFalse(cv.contains("Employment History"));
        assertTrue(cv.indexOf("Technical Profile")
                < cv.indexOf("Projects"));
        assertTrue(cv.indexOf("Projects")
                < cv.indexOf("Technical Skills"));
        assertTrue(coverLetter.contains(
                "Application for Java Developer at Example Ltd"));
        assertTrue(coverLetter.endsWith("Yours faithfully,"));
    }

    @Test
    void approvedRollbackSchemaRemainsUsableWithAnEvidenceCatalogue()
            throws Exception {
        JsonNode rollbackSchema;
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.4.0/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException(
                        "Rollback output schema fixture is missing.");
            }
            rollbackSchema = objectMapper.readTree(input);
        }
        ObjectNode output = validOutput();
        ((ObjectNode) output.path("cv")).remove("projects");
        ArrayNode skills = (ArrayNode) output.at("/cv/coreSkills");
        for (int index = 0; index < 13; index++) {
            ObjectNode skill = objectMapper.createObjectNode();
            skill.put("name", "Java");
            skill.put("evidence", "Java");
            skills.add(skill);
        }
        ObjectNode claim = objectMapper.createObjectNode();
        claim.put("claimId", "CLAIM-011");
        claim.put("disposition", "SUPPORTED");
        claim.putArray("evidenceIds").add("PROFILE.SKILL.1");
        claim.putArray("contentPaths").add("/cv/coreSkills");
        claim.put("reviewText", "");
        ((ArrayNode) output.path("claims")).add(claim);

        GeneratedApplicationDocuments accepted = parser.parse(
                objectMapper.writeValueAsString(output),
                rollbackSchema,
                catalog);

        assertEquals(13, accepted.getCv().getCoreSkills().size());
    }

    @Test
    void versionedEvidenceIsStableAndCannotCrossDocumentPurposes()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();

        GeneratedApplicationDocuments accepted = parse(output);
        assertEquals(11, accepted.getClaims().size());

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

        assertEquals(11, accepted.getClaims().size());
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
                        "/coverLetter/bodyParagraphs/1",
                        "/coverLetter/bodyParagraphs/2"),
                accepted.getClaims().get(6).getContentPaths());
    }

    @Test
    void rejectsSkillListsAboveTheGovernedQualityLimit()
            throws Exception {
        ObjectNode output = validOutput();
        ArrayNode skills = (ArrayNode) output.at("/cv/coreSkills");
        for (int index = 0; index < 13; index++) {
            ObjectNode skill = objectMapper.createObjectNode();
            skill.put("name", "Java");
            skill.put("evidence", "");
            skills.add(skill);
        }

        assertRejected(output, "$.cv.coreSkills");
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
    void preservesSupportedSkillNarrativeInsteadOfReplacingItWithAnArbitraryFact()
            throws Exception {
        ObjectNode output = validOutput();
        ObjectNode skill = objectMapper.createObjectNode();
        skill.put("name", "Java");
        skill.put("evidence", "Built useful Java services.");
        ((ArrayNode) output.at("/cv/coreSkills")).add(skill);
        ObjectNode claim = objectMapper.createObjectNode();
        claim.put("claimId", "CLAIM-011");
        claim.put("disposition", "REWORDED");
        claim.putArray("evidenceIds")
                .add("PROFILE.SKILL.1")
                .add("PROFILE.EMPLOYMENT.1.RESPONSIBILITIES");
        claim.putArray("contentPaths")
                .add("/cv/coreSkills/0/name")
                .add("/cv/coreSkills/0/evidence");
        claim.put("reviewText", "");
        ((ArrayNode) output.path("claims")).add(claim);

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                "Built useful Java services.",
                accepted.getCv().getCoreSkills().get(0).getEvidence());
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

    @Test
    void acceptsEducationProgrammeAsAnExactQualificationNameFact()
            throws Exception {
        String educationNameId = "EVIDENCE.EDUCATION.NAME";
        addSnapshotFact(
                educationNameId,
                "PROGRAMME_OR_SUBJECT",
                "Music Technology",
                "EDUCATION");
        ObjectNode output = validOutput();
        addQualification(output, "Music Technology", "2024");
        ((ArrayNode) output.at("/claims/10/evidenceIds"))
                .set(
                        0,
                        objectMapper.getNodeFactory().textNode(
                                educationNameId));

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                "Music Technology",
                accepted.getCv().getQualifications().get(0)
                        .getQualificationName());
    }

    @Test
    void acceptsPresentOnlyWhenGroundedByAnExactOngoingEndDateFact()
            throws Exception {
        String presentId = "EVIDENCE.EMPLOYMENT.PRESENT";
        addSnapshotFact(
                presentId,
                "END_DATE",
                "Present",
                "EMPLOYMENT");
        ObjectNode output = validOutput();
        ObjectNode history = objectMapper.createObjectNode();
        history.put("jobTitle", "Software Engineer");
        history.put("employer", "Example Ltd");
        history.put("startDate", "2022-03");
        history.put("endDate", "Present");
        history.putArray("responsibilities")
                .add("Built and maintained Java services.");
        history.put("tailoredDescription", "");
        ((ArrayNode) output.at("/cv/workHistory")).add(history);

        ObjectNode claim = objectMapper.createObjectNode();
        claim.put("claimId", "CLAIM-011");
        claim.put("disposition", "SUPPORTED");
        claim.putArray("evidenceIds")
                .add("PROFILE.EMPLOYMENT.1.JOB_TITLE")
                .add("PROFILE.EMPLOYMENT.1.EMPLOYER")
                .add("PROFILE.EMPLOYMENT.1.START_DATE")
                .add(presentId)
                .add("PROFILE.EMPLOYMENT.1.RESPONSIBILITIES");
        claim.putArray("contentPaths")
                .add("/cv/workHistory/0/jobTitle")
                .add("/cv/workHistory/0/employer")
                .add("/cv/workHistory/0/startDate")
                .add("/cv/workHistory/0/endDate")
                .add("/cv/workHistory/0/responsibilities/0");
        claim.put("reviewText", "");
        ((ArrayNode) output.path("claims")).add(claim);

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                "Present",
                accepted.getCv().getWorkHistory().get(0).getEndDate());
    }

    private void addSnapshotFact(
            String evidenceId,
            String factType,
            String value,
            String category) {
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        records.add(new ApprovedEvidenceRecord(
                evidenceId,
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/cv/selections/0/facts/0",
                value,
                factType,
                category,
                EvidencePurpose.CV));
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());
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

    private void useVersionedCatalog() {
        catalog = new ClaimEvidenceCatalogFactory().create(
                new GenerationInputNormalizer(
                        Clock.fixed(
                                Instant.parse("2026-07-24T13:00:00Z"),
                                ZoneOffset.UTC))
                        .normalize(
                                "owner-secret",
                                validVersionedRequest()));
    }

    private ObjectNode validOutput() throws Exception {
        return (ObjectNode) objectMapper.readTree(CvCoverLetterServiceTest.validJson());
    }

    private ObjectNode versionedOutput() throws Exception {
        ObjectNode output = validOutput();
        String cvFact = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.CV_SKILL_FACT_ID.toString();
        String projectTitleFact = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.CV_PROJECT_TITLE_FACT_ID
                .toString();
        String projectDescriptionFact = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.CV_PROJECT_FACT_ID
                .toString();
        String coverFact = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.COVER_EXPERIENCE_FACT_ID
                .toString();
        ObjectNode skill = objectMapper.createObjectNode();
        skill.put("name", "Java");
        skill.put("evidence", "");
        ((ArrayNode) output.at("/cv/coreSkills")).add(skill);
        ObjectNode project = objectMapper.createObjectNode();
        project.put("title", "Job Seeker Copilot");
        project.put("role", "");
        project.put("context", "");
        project.put("startDate", "");
        project.put("endDate", "");
        project.put("description", "Built useful services.");
        project.putArray("highlights");
        ((ArrayNode) output.at("/cv/projects")).add(project);
        ArrayNode cvEvidence =
                (ArrayNode) output.at("/claims/1/evidenceIds");
        cvEvidence.set(
                0,
                objectMapper.getNodeFactory().textNode(cvFact));
        cvEvidence.add(projectTitleFact);
        cvEvidence.add(projectDescriptionFact);
        ((ArrayNode) output.at("/claims/1/contentPaths"))
                .add("/cv/coreSkills/0/name")
                .add("/cv/projects/0/title")
                .add("/cv/projects/0/description");
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

    private void removeContentPath(
            ObjectNode output,
            int claimIndex,
            String path
    ) {
        ArrayNode contentPaths =
                (ArrayNode) output.at(
                        "/claims/" + claimIndex + "/contentPaths");
        for (int index = contentPaths.size() - 1; index >= 0; index--) {
            if (contentPaths.get(index).asText().equals(path)) {
                contentPaths.remove(index);
            }
        }
    }

    private void removeEvidenceId(
            ObjectNode output,
            int claimIndex,
            String evidenceId
    ) {
        ArrayNode evidenceIds =
                (ArrayNode) output.at(
                        "/claims/" + claimIndex + "/evidenceIds");
        for (int index = evidenceIds.size() - 1; index >= 0; index--) {
            if (evidenceIds.get(index).asText().equals(evidenceId)) {
                evidenceIds.remove(index);
            }
        }
    }

    private void removeClaimsCovering(
            ObjectNode output,
            java.util.Set<String> contentPaths
    ) {
        ArrayNode claims = (ArrayNode) output.path("claims");
        for (int claimIndex = claims.size() - 1;
                claimIndex >= 0;
                claimIndex--) {
            boolean covered = false;
            for (JsonNode contentPath :
                    claims.get(claimIndex).path("contentPaths")) {
                if (contentPaths.contains(contentPath.asText())) {
                    covered = true;
                    break;
                }
            }
            if (covered) {
                claims.remove(claimIndex);
            }
        }
    }

    private ClaimEvidenceCatalog withoutEvidence(
            String evidenceId
    ) {
        return new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                catalog.records().stream()
                        .filter(record ->
                                !record.evidenceId()
                                        .equals(evidenceId))
                        .toList(),
                catalog.sectionOrder());
    }

    private void addCoverSelectionSharingEvidenceId(
            String evidenceId
    ) {
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        records.add(new ApprovedEvidenceRecord(
                evidenceId,
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/coverLetter/selections/1/facts/0",
                "Led community workshops.",
                "DESCRIPTION",
                "VOLUNTEERING",
                EvidencePurpose.COVER_LETTER));
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());
    }

    private void replaceEvidenceValue(
            String evidenceId,
            String value
    ) {
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                catalog.records().stream()
                        .map(record ->
                                record.evidenceId()
                                                .equals(evidenceId)
                                        ? new ApprovedEvidenceRecord(
                                                record.evidenceId(),
                                                record.source(),
                                                record.sourcePath(),
                                                value,
                                                record.factType(),
                                                record.category(),
                                                record.purpose())
                                        : record)
                        .toList(),
                catalog.sectionOrder());
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
