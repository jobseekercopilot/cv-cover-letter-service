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
import com.jobseekercopilot.cvcoverletter.dto.GeneratedClaim;
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
                throw new IllegalStateException("Rollback claim evidence schema fixture is missing.");
            }
            schema = objectMapper.readTree(input);
        }
        catalog = new ClaimEvidenceCatalogFactory().create(
                new GenerationInputNormalizer(
                        Clock.fixed(Instant.parse("2026-07-24T13:00:00Z"), ZoneOffset.UTC))
                        .normalize("owner-secret", validRequest()));
    }

    @Test
    void supplementalEvidenceNormalizationCannotExceedTheSchemaBound() {
        List<String> primary = new java.util.ArrayList<>();
        for (int index = 0; index < 29; index++) {
            primary.add("PRIMARY-" + index);
        }

        List<String> normalized =
                new ClaimEvidenceValidator().mergeBoundedEvidenceReferences(
                        primary,
                        List.of(
                                "PRIMARY-0",
                                "SUPPLEMENTAL-1",
                                "SUPPLEMENTAL-2"));

        assertEquals(30, normalized.size());
        assertEquals(primary, normalized.subList(0, primary.size()));
        assertEquals("SUPPLEMENTAL-1", normalized.get(29));
        assertFalse(normalized.contains("SUPPLEMENTAL-2"));

        List<String> invalidProviderReferences = new java.util.ArrayList<>(primary);
        invalidProviderReferences.add("PRIMARY-29");
        invalidProviderReferences.add("PRIMARY-30");
        assertEquals(
                invalidProviderReferences,
                new ClaimEvidenceValidator().mergeBoundedEvidenceReferences(
                        invalidProviderReferences,
                        List.of("SUPPLEMENTAL-1")));
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
    void restoresGroundedNarrativeCoverageButStillRejectsIdentityOmissions()
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

        java.util.Set<String> identityPaths = java.util.Set.of(
                "/cv/title",
                "/cv/personalSummary",
                "/coverLetter/title",
                "/coverLetter/closingParagraph");
        identityPaths.forEach(path ->
                assertTrue(error.getMessage().contains(path), error.getMessage()));
    }

    @Test
    void doesNotTreatGenerationNotesAsFinalClaimCoverage()
            throws Exception {
        ObjectNode output = validOutput();
        ArrayNode claims = (ArrayNode) output.path("claims");
        JsonNode removedClaim = claims.remove(8);
        String omittedPath =
                removedClaim.path("contentPaths").get(0).asText();
        ((ArrayNode) output.at("/generationNotes/missingInformation"))
                .add(omittedPath);

        InvalidLlmResponseException error =
                assertRejected(
                        output,
                        "final content contains unaccounted claim paths");

        assertTrue(error.getMessage().contains(omittedPath));
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
    void activeSchemaRequiresSupportedIsolatedCanonicalBookendClaims()
            throws Exception {
        useVersionedCatalog();
        useSchemaFixture("cv-cover-letter-1.5.3");

        GeneratedApplicationDocuments accepted =
                parse(activeVersionedOutput());
        assertEquals(
                "Please consider my application for this role.",
                accepted.getCoverLetter().getOpeningParagraph());

        ObjectNode claimantEvidence = activeVersionedOutput();
        ((ArrayNode) claimantEvidence.at(
                        "/claims/5/evidenceIds"))
                .add(com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .COVER_EXPERIENCE_FACT_ID.toString());
        assertRejected(
                claimantEvidence,
                "canonical application bookend must cite exactly canonical evidence");

        ObjectNode groupedBookend = activeVersionedOutput();
        ((ObjectNode) groupedBookend.at("/claims/5"))
                .withArray("contentPaths")
                .add("/coverLetter/bodyParagraphs/0");
        assertRejected(
                groupedBookend,
                "canonical application bookend claim must be isolated");

        ObjectNode rewordedBookend = activeVersionedOutput();
        ((ObjectNode) rewordedBookend.at("/claims/5"))
                .put("disposition", "REWORDED");
        assertRejected(
                rewordedBookend,
                "canonical application bookend must be supported");
    }

    @Test
    void activeSchemaEnforcesCanonicalBookendsForLegacyCatalogs()
            throws Exception {
        useSchemaFixture("cv-cover-letter-1.5.3");
        ObjectNode output = validOutput();
        ((ObjectNode) output.at("/claims/5"))
                .put("disposition", "REWORDED");

        assertRejected(
                output,
                "canonical application bookend must be supported");
    }

    @Test
    void rollbackSchemaKeepsVersionedCanonicalTextAsAnOrdinaryClaim()
            throws Exception {
        useVersionedCatalog();
        useSchemaFixture("cv-cover-letter-1.5.2");
        ObjectNode output = activeVersionedOutput();
        ((ArrayNode) output.at("/claims/5/evidenceIds"))
                .add(com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .COVER_EXPERIENCE_FACT_ID.toString());

        GeneratedApplicationDocuments accepted = parse(output);

        assertTrue(accepted.getClaims().stream()
                .filter(claim -> claim.getContentPaths()
                        .equals(List.of(
                                "/coverLetter/openingParagraph")))
                .findFirst()
                .orElseThrow()
                .getEvidenceIds()
                .contains(com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .COVER_EXPERIENCE_FACT_ID.toString()));
    }

    @Test
    void fixedTextDoesNotChangeLegacyEvidenceHandling()
            throws Exception {
        ObjectNode output = validOutput();
        ((ObjectNode) output.at("/coverLetter"))
                .put(
                        "closingParagraph",
                        "Thank you for considering my application.");
        ((ArrayNode) output.at("/claims/8/evidenceIds"))
                .removeAll()
                .add("JOB.DESCRIPTION");

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
    void acceptsARewordedProjectGroundedByOneProjectSelectionDespiteExtraCitations()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ((ObjectNode) output.at("/cv/projects/0"))
                .put("title", "Job Seeker Copilot Platform");
        String qualificationFactId =
                "81000000-0000-4000-8000-000000000002";
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

        String projectFactId = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.CV_PROJECT_FACT_ID.toString();
        assertTrue(accepted.getClaims().stream()
                .filter(claim -> claim.getContentPaths().stream()
                        .anyMatch(path -> path.startsWith(
                                "/cv/projects/0/")))
                .anyMatch(claim -> claim.getEvidenceIds()
                        .contains(projectFactId)));
        assertTrue(accepted.getClaims().stream()
                .filter(claim -> claim.getContentPaths().contains(
                        "/cv/qualifications/0/qualificationName"))
                .anyMatch(claim -> claim.getEvidenceIds()
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
        String invalidPath =
                "/cv/projects/9999999999/title";
        ((ArrayNode) output.at("/claims/1/contentPaths"))
                .add(invalidPath);

        InvalidLlmResponseException error = assertRejected(
                output,
                "content path is not an approved final claim path");
        assertTrue(error.getMessage().contains(invalidPath));
    }

    @Test
    void boundsAnUnapprovedContentPathInTheValidationError()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        String invalidPath =
                "/cv/" + "unexpected/".repeat(30) + "field";
        ((ArrayNode) output.at("/claims/1/contentPaths"))
                .add(invalidPath);

        InvalidLlmResponseException error = assertRejected(
                output,
                "content path is not an approved final claim path");
        assertTrue(error.getMessage().contains("..."));
        assertFalse(error.getMessage().contains(invalidPath));
    }

    @Test
    void discardsPointersToExistingEmptyProjectContent()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ArrayNode contentPaths =
                (ArrayNode) output.at("/claims/1/contentPaths");
        contentPaths.add("/cv/projects/0/endDate");
        contentPaths.add("/cv/projects/0/highlights");

        GeneratedApplicationDocuments accepted = parse(output);

        assertTrue(accepted.getClaims().stream()
                .flatMap(claim ->
                        claim.getContentPaths().stream())
                .noneMatch(path ->
                        path.equals("/cv/projects/0/endDate")
                                || path.equals(
                                        "/cv/projects/0/highlights")));
    }

    @Test
    void discardsAnExistingEmptyQualificationLeafAlongsidePopulatedContent()
            throws Exception {
        useVersionedCatalog();
        String qualificationFactId =
                "84000000-0000-4000-8000-000000000001";
        addSnapshotFact(
                qualificationFactId,
                "QUALIFICATION_TITLE",
                "BSc Computing",
                "QUALIFICATION");
        ObjectNode output = versionedOutput();
        ObjectNode qualification = objectMapper.createObjectNode();
        qualification.put("qualificationName", "BSc Computing");
        qualification.put("issuingBody", "");
        qualification.put("status", "");
        qualification.put("grade", "");
        qualification.put("dateAchieved", "");
        qualification.put("expectedCompletion", "");
        ((ArrayNode) output.at("/cv/qualifications"))
                .add(qualification);
        ObjectNode claim = objectMapper.createObjectNode();
        claim.put("claimId", "CLAIM-099");
        claim.put("disposition", "SUPPORTED");
        claim.putArray("evidenceIds").add(qualificationFactId);
        claim.putArray("contentPaths")
                .add("/cv/qualifications/0/qualificationName")
                .add("/cv/qualifications/0/expectedCompletion");
        claim.put("reviewText", "");
        ((ArrayNode) output.path("claims")).add(claim);

        GeneratedApplicationDocuments accepted = parse(output);

        assertTrue(accepted.getClaims().stream()
                .flatMap(acceptedClaim ->
                        acceptedClaim.getContentPaths().stream())
                .noneMatch(
                        "/cv/qualifications/0/expectedCompletion"::equals));
        assertEquals(
                "BSc Computing",
                accepted.getCv()
                        .getQualifications()
                        .get(0)
                        .getQualificationName());
    }

    @Test
    void rejectsAStandaloneFinalClaimForOnlyEmptyContent()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ObjectNode claim = objectMapper.createObjectNode();
        claim.put("claimId", "CLAIM-099");
        claim.put("disposition", "SUPPORTED");
        claim.putArray("evidenceIds")
                .add(com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .CV_PROJECT_TITLE_FACT_ID.toString());
        claim.putArray("contentPaths")
                .add("/cv/projects/0/endDate")
                .add("/cv/projects/0/highlights");
        claim.put("reviewText", "");
        ((ArrayNode) output.path("claims")).add(claim);

        assertRejected(
                output,
                "final claim has no populated content path");
    }

    @Test
    void anEmptyOnlyClaimCannotHideUnknownEvidence()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ObjectNode claim = objectMapper.createObjectNode();
        claim.put("claimId", "CLAIM-099");
        claim.put("disposition", "SUPPORTED");
        claim.putArray("evidenceIds")
                .add("83000000-0000-4000-8000-000000000099");
        claim.putArray("contentPaths")
                .add("/cv/projects/0/endDate");
        claim.put("reviewText", "");
        ((ArrayNode) output.path("claims")).add(claim);

        assertRejected(
                output,
                "evidence ID is not approved");
    }

    @Test
    void anEmptyOnlyClaimCannotHideDuplicateEvidence()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ObjectNode claim = objectMapper.createObjectNode();
        String evidenceId =
                com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .CV_PROJECT_TITLE_FACT_ID.toString();
        claim.put("claimId", "CLAIM-099");
        claim.put("disposition", "SUPPORTED");
        claim.putArray("evidenceIds")
                .add(evidenceId)
                .add(evidenceId);
        claim.putArray("contentPaths")
                .add("/cv/projects/0/endDate");
        claim.put("reviewText", "");
        ((ArrayNode) output.path("claims")).add(claim);

        assertRejected(
                output,
                "evidence ID is duplicated");
    }

    @Test
    void rejectsServerOwnedNonClaimContent()
            throws Exception {
        useVersionedCatalog();
        ObjectNode greeting = versionedOutput();
        ((ArrayNode) greeting.at("/claims/5/contentPaths"))
                .add("/coverLetter/greeting");

        assertRejected(
                greeting,
                "content path is not an approved final claim path");

        ObjectNode signOff = versionedOutput();
        ((ArrayNode) signOff.at("/claims/5/contentPaths"))
                .add("/coverLetter/signOff");

        assertRejected(
                signOff,
                "content path is not an approved final claim path");
    }

    @Test
    void rejectsUnknownOutOfRangeAndScalarDescendantContentPaths()
            throws Exception {
        useVersionedCatalog();
        for (String invalidPath : List.of(
                "/cv/projects/0/unknownField",
                "/cv/projects/1/title",
                "/cv/projects/0/title/0")) {
            ObjectNode output = versionedOutput();
            ((ArrayNode) output.at("/claims/1/contentPaths"))
                    .add(invalidPath);

            InvalidLlmResponseException error = assertRejected(
                    output,
                    "content path is not an approved final claim path");
            assertTrue(
                    error.getMessage().contains(invalidPath),
                    error.getMessage());
        }
    }

    @Test
    void anEmptyPathOnAReviewOnlyClaimStillRejectsAsFinalContent()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ObjectNode claim = reviewOnlyClaim(
                "CLAIM-099",
                "CONFIRMATION_REQUIRED",
                "Please confirm the project end date.");
        claim.withArray("contentPaths")
                .add("/cv/projects/0/endDate");
        ((ArrayNode) output.path("claims")).add(claim);

        assertRejected(
                output,
                "review-only claim points at final content");
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
    void unusedSelectedEvidenceDoesNotRejectAnOtherwiseGroundedDraft()
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

        GeneratedApplicationDocuments accepted = parse(output);

        assertFalse(accepted.getClaims().stream()
                .flatMap(claim -> claim.getEvidenceIds().stream())
                .anyMatch("85000000-0000-4000-8000-000000000001"::equals));
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
    void reservesExactAtomicEvidenceWhenSubmittedReferencesFillTheLimit()
            throws Exception {
        ObjectNode output = validOutput();
        ((ObjectNode) output.path("coverLetter"))
                .put("jobTitle", "Senior Architect");
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        ArrayNode submittedEvidence =
                (ArrayNode) output.at("/claims/3/evidenceIds");
        submittedEvidence.removeAll();
        for (int index = 0; index < 30; index++) {
            String evidenceId = "JOB.SUPPLEMENTAL." + index;
            records.add(new ApprovedEvidenceRecord(
                    evidenceId,
                    EvidenceSource.JOB,
                    "/job/description",
                    "Supplemental job context " + index,
                    "DESCRIPTION",
                    "JOB",
                    EvidencePurpose.COVER_LETTER));
            submittedEvidence.add(evidenceId);
        }
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());

        GeneratedApplicationDocuments corrected = parse(output);

        GeneratedClaim jobTitleClaim = corrected.getClaims().stream()
                .filter(claim -> claim.getContentPaths()
                        .contains("/coverLetter/jobTitle"))
                .findFirst()
                .orElseThrow();
        assertEquals("Java Developer", corrected.getCoverLetter().getJobTitle());
        assertEquals(30, jobTitleClaim.getEvidenceIds().size());
        assertEquals("JOB.TITLE", jobTitleClaim.getEvidenceIds().get(0));
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
    void rejectsALiteralSkillsListInTheCoverLetterButNotNaturalProse()
            throws Exception {
        ObjectNode listed = validOutput();
        ((ArrayNode) listed.at("/coverLetter/bodyParagraphs"))
                .set(
                        0,
                        objectMapper.getNodeFactory().textNode(
                                "Technical Skills: Java"));

        assertRejected(
                listed,
                "cover letter contains a literal skills list");

        ObjectNode standaloneHeading = validOutput();
        ((ArrayNode) standaloneHeading.at("/coverLetter/bodyParagraphs"))
                .set(
                        0,
                        objectMapper.getNodeFactory().textNode("Key Skills"));
        assertRejected(
                standaloneHeading,
                "cover letter contains a literal skills list");

        ObjectNode dashHeading = validOutput();
        ((ArrayNode) dashHeading.at("/coverLetter/bodyParagraphs"))
                .set(
                        0,
                        objectMapper.getNodeFactory().textNode(
                                "Skills & Expertise – Java"));
        assertRejected(
                dashHeading,
                "cover letter contains a literal skills list");

        parse(validOutput());
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
    void keepsOnlyTheRelevantProposedSkillsWithoutFillingAnArbitraryMinimum()
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

        GeneratedApplicationDocuments accepted = parse(versionedOutput());

        assertEquals(
                List.of("Java"),
                accepted.getCv().getCoreSkills().stream()
                        .map(skill -> skill.getName())
                        .toList());
    }

    @Test
    void activePolicyProjectsOnlyCvDemonstratedSkillsInDeterministicOrder()
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
        records.add(new ApprovedEvidenceRecord(
                "83000000-0000-4000-8000-000000000001",
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/coverLetter/selections/0/facts/1",
                "Rust",
                "DEMONSTRATED_SKILL",
                "VOLUNTEERING",
                EvidencePurpose.COVER_LETTER));
        records.add(new ApprovedEvidenceRecord(
                "83000000-0000-4000-8000-000000000002",
                EvidenceSource.EVIDENCE_SNAPSHOT,
                "/evidenceSnapshots/cv/selections/0/facts/11",
                "Go",
                "DESCRIPTION",
                "PROJECT",
                EvidencePurpose.CV));
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());
        ObjectNode output = versionedOutput();
        ArrayNode proposed = (ArrayNode) output.at("/cv/coreSkills");
        proposed.removeAll();
        for (String name : List.of(
                "Kotlin", "Rust", "Kotlin", "Java", "Go")) {
            ObjectNode skill = proposed.addObject();
            skill.put("name", name);
            skill.put("evidence", "");
        }
        GeneratedApplicationDocuments documents = objectMapper.treeToValue(
                output,
                GeneratedApplicationDocuments.class);

        new ClaimEvidenceValidator().validate(
                output,
                documents,
                catalog,
                false,
                true);

        assertEquals(
                List.of("Kotlin", "Java"),
                output.at("/cv/coreSkills").findValues("name").stream()
                        .map(JsonNode::asText)
                        .toList());
        assertTrue(output.at("/cv/coreSkills").findValues("evidence")
                .stream()
                .allMatch(value -> value.asText().isEmpty()));
        assertTrue(claimById(documents, "CLAIM-002")
                .getContentPaths().stream()
                .noneMatch(path -> path.startsWith("/cv/coreSkills")));
        java.util.Map<String, String> firstSkillEvidenceByValue =
                new java.util.LinkedHashMap<>();
        catalog.records().stream()
                .filter(record -> record.source()
                        == EvidenceSource.EVIDENCE_SNAPSHOT)
                .filter(record -> record.purpose()
                        == EvidencePurpose.CV)
                .filter(record -> "DEMONSTRATED_SKILL".equals(
                        record.factType()))
                .forEach(record -> firstSkillEvidenceByValue.putIfAbsent(
                        record.value(),
                        record.evidenceId()));
        for (int index = 0; index < 2; index++) {
            String path = "/cv/coreSkills/" + index + "/name";
            GeneratedClaim skillClaim = documents.getClaims().stream()
                    .filter(claim -> claim.getContentPaths().equals(
                            List.of(path)))
                    .findFirst()
                    .orElseThrow();
            assertEquals(ClaimDisposition.SUPPORTED,
                    skillClaim.getDisposition());
            assertEquals(
                    List.of(firstSkillEvidenceByValue.get(
                            output.at(path).asText())),
                    skillClaim.getEvidenceIds());
        }
        assertTrue(documents.getClaims().size() <= 40);
        GeneratedApplicationDocuments projected = objectMapper.treeToValue(
                output,
                GeneratedApplicationDocuments.class);
        projected.setClaims(documents.getClaims());
        new GeneratedDocumentQualityValidator().validate(
                output,
                projected,
                catalog);
    }

    @Test
    void versionedProjectionCombinesDeclaredAndDemonstratedSkillsWithoutJobOnlySkills()
            throws Exception {
        var request = validVersionedRequest();
        request.getProfile().setSkills(List.of(
                "Java",
                "Spring",
                "Unproposed profile skill"));
        request.getJob().setDescription(
                "The successful applicant will use Kubernetes.");
        catalog = new ClaimEvidenceCatalogFactory().create(
                new GenerationInputNormalizer(
                        Clock.fixed(
                                Instant.parse("2026-07-24T13:00:00Z"),
                                ZoneOffset.UTC))
                        .normalize("owner-secret", request));
        ObjectNode output = versionedOutput();
        ArrayNode proposed = (ArrayNode) output.at("/cv/coreSkills");
        proposed.removeAll();
        for (String name : List.of("Spring", "Java", "Kubernetes")) {
            ObjectNode skill = proposed.addObject();
            skill.put("name", name);
            skill.put("evidence", "");
        }
        GeneratedApplicationDocuments documents = objectMapper.treeToValue(
                output,
                GeneratedApplicationDocuments.class);

        new ClaimEvidenceValidator().validate(
                output,
                documents,
                catalog,
                false,
                true);

        assertEquals(
                List.of("Spring", "Java"),
                output.at("/cv/coreSkills").findValues("name").stream()
                        .map(JsonNode::asText)
                        .toList());
        String springEvidenceId = catalog.records().stream()
                .filter(record -> record.source()
                        == EvidenceSource.PROFILE_REVISION)
                .filter(record -> record.value().equals("Spring"))
                .map(ApprovedEvidenceRecord::evidenceId)
                .findFirst()
                .orElseThrow();
        assertEquals(
                List.of(springEvidenceId),
                documents.getClaims().stream()
                        .filter(claim -> claim.getContentPaths().equals(
                                List.of("/cv/coreSkills/0/name")))
                        .findFirst()
                        .orElseThrow()
                        .getEvidenceIds());
        assertEquals(
                List.of(com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures.CV_SKILL_FACT_ID.toString()),
                documents.getClaims().stream()
                        .filter(claim -> claim.getContentPaths().equals(
                                List.of("/cv/coreSkills/1/name")))
                        .findFirst()
                        .orElseThrow()
                        .getEvidenceIds());
    }

    @Test
    void activeParserRendersADeclaredOnlySkillWithoutAddingACoverLetterList()
            throws Exception {
        var request = validVersionedRequest();
        request.getProfile().setSkills(List.of("Spring"));
        request.getEvidenceSnapshots().getCv().getSelections().get(0)
                .setFacts(new java.util.ArrayList<>(
                        request.getEvidenceSnapshots().getCv()
                                .getSelections().get(0).getFacts().stream()
                                .filter(fact -> !"DEMONSTRATED_SKILL".equals(
                                        fact.getFactType()))
                                .toList()));
        catalog = new ClaimEvidenceCatalogFactory().create(
                new GenerationInputNormalizer(
                        Clock.fixed(
                                Instant.parse("2026-07-24T13:00:00Z"),
                                ZoneOffset.UTC))
                        .normalize("owner-secret", request));
        useSchemaFixture("cv-cover-letter-1.5.8");
        ObjectNode output = (ObjectNode) objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) output.at("/cv"))
                .put("personalSummary",
                        "A developer focused on useful services.");
        ObjectNode skill = ((ArrayNode) output.at("/cv/coreSkills"))
                .addObject();
        skill.put("name", "Spring");
        skill.put("evidence", "");
        ObjectNode project = ((ArrayNode) output.at("/cv/projects"))
                .addObject();
        project.put("title", "Job Seeker Copilot");
        project.put("role", "");
        project.put("context", "");
        project.put("startDate", "");
        project.put("endDate", "");
        project.put("description", "Built useful services.");
        project.putArray("highlights");
        String cvTitleFact = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.CV_PROJECT_TITLE_FACT_ID.toString();
        String cvDescriptionFact = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.CV_PROJECT_FACT_ID.toString();
        String coverFact = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.COVER_EXPERIENCE_FACT_ID.toString();
        ArrayNode summaryEvidence = (ArrayNode) output.at(
                "/personalSummaryClaim/evidenceIds");
        summaryEvidence.removeAll();
        summaryEvidence.add(cvDescriptionFact);
        for (int claimIndex : List.of(3, 4)) {
            ArrayNode evidenceIds = (ArrayNode) output.at(
                    "/claims/" + claimIndex + "/evidenceIds");
            evidenceIds.removeAll();
            evidenceIds.add(coverFact);
        }
        ObjectNode projectClaim = ((ArrayNode) output.path("claims"))
                .addObject();
        projectClaim.put("claimId", "CLAIM-020");
        projectClaim.put("disposition", "SUPPORTED");
        projectClaim.putArray("evidenceIds")
                .add(cvTitleFact)
                .add(cvDescriptionFact);
        projectClaim.putArray("contentPaths")
                .add("/cv/projects/0/title")
                .add("/cv/projects/0/description");
        projectClaim.put("reviewText", "");

        GeneratedApplicationDocuments accepted = parse(output);
        String renderedCv = new CvDocumentRenderer().render(
                accepted.getCv());
        String renderedCoverLetter = new CoverLetterDocumentRenderer().render(
                accepted.getCoverLetter());

        assertEquals(
                List.of("Spring"),
                accepted.getCv().getCoreSkills().stream()
                        .map(generatedSkill -> generatedSkill.getName())
                        .toList());
        assertTrue(renderedCv.contains("Technical Skills\nSpring"));
        assertFalse(renderedCoverLetter.contains("Technical Skills"));
        assertFalse(renderedCoverLetter.contains("Key Skills"));
    }

    @Test
    void revisionDeclaredSkillsCannotBeCitedByModelAuthoredNarrative()
            throws Exception {
        var request = validVersionedRequest();
        request.getProfile().setSkills(List.of("Spring"));
        catalog = new ClaimEvidenceCatalogFactory().create(
                new GenerationInputNormalizer(
                        Clock.fixed(
                                Instant.parse("2026-07-24T13:00:00Z"),
                                ZoneOffset.UTC))
                        .normalize("owner-secret", request));
        String declaredEvidenceId = catalog.records().stream()
                .filter(record -> record.source()
                        == EvidenceSource.PROFILE_REVISION)
                .map(ApprovedEvidenceRecord::evidenceId)
                .findFirst()
                .orElseThrow();
        ObjectNode output = versionedOutput();
        ((ArrayNode) output.at("/claims/6/evidenceIds"))
                .add(declaredEvidenceId);

        assertRejected(
                output,
                "revision-declared skill evidence is service-projected only");
    }

    @Test
    void projectedSkillProvenanceKeepsOneOrderedFactWhenNamesRepeat()
            throws Exception {
        useVersionedCatalog();
        List<ApprovedEvidenceRecord> records =
                new java.util.ArrayList<>(catalog.records());
        for (int index = 0; index < 31; index++) {
            records.add(new ApprovedEvidenceRecord(
                    String.format(
                            "84000000-0000-4000-8000-%012d",
                            index),
                    EvidenceSource.EVIDENCE_SNAPSHOT,
                    "/evidenceSnapshots/cv/selections/1/facts/"
                            + index,
                    "Kotlin",
                    "DEMONSTRATED_SKILL",
                    "PROJECT",
                    EvidencePurpose.CV));
        }
        catalog = new ClaimEvidenceCatalog(
                catalog.catalogVersion(),
                List.copyOf(records),
                catalog.sectionOrder());
        ObjectNode output = versionedOutput();
        ArrayNode proposed = (ArrayNode) output.at("/cv/coreSkills");
        proposed.removeAll();
        ObjectNode kotlin = proposed.addObject();
        kotlin.put("name", "Kotlin");
        kotlin.put("evidence", "");
        GeneratedApplicationDocuments documents = objectMapper.treeToValue(
                output,
                GeneratedApplicationDocuments.class);

        new ClaimEvidenceValidator().validate(
                output,
                documents,
                catalog,
                false,
                true);

        GeneratedClaim kotlinClaim = documents.getClaims().stream()
                .filter(claim -> claim.getContentPaths().equals(
                        List.of("/cv/coreSkills/0/name")))
                .findFirst()
                .orElseThrow();
        assertEquals(
                List.of("84000000-0000-4000-8000-000000000000"),
                kotlinClaim.getEvidenceIds());
        assertTrue(documents.getClaims().size() <= 40);
    }

    @Test
    void acceptsASelectedEvidenceEntryMissingFromFinalContent()
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

        GeneratedApplicationDocuments accepted = parse(versionedOutput());

        assertFalse(accepted.getClaims().stream()
                .flatMap(claim -> claim.getEvidenceIds().stream())
                .anyMatch("82000000-0000-4000-8000-000000000001"::equals));
    }

    @Test
    void cvClaimWithSharedStableIdMayLeaveCoverLetterSelectionUnused()
            throws Exception {
        useVersionedCatalog();
        String sharedId = com.jobseekercopilot.cvcoverletter
                .GenerationInputFixtures.CV_PROJECT_FACT_ID.toString();
        addCoverSelectionSharingEvidenceId(sharedId);

        GeneratedApplicationDocuments accepted = parse(versionedOutput());

        assertTrue(accepted.getClaims().stream()
                .filter(claim -> claim.getEvidenceIds().contains(sharedId))
                .allMatch(claim -> claim.getContentPaths().stream()
                        .allMatch(path -> path.startsWith("/cv/"))));
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
    void acceptsGroundedQualificationEvidenceAcrossCoverParagraphs()
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

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(2, accepted.getCoverLetter().getBodyParagraphs().stream()
                .filter(paragraph -> paragraph.contains(
                        "BMus Music Performance / Composition"))
                .count());
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
    void normalizesACompleteVersionedOneBasedBodyParagraphLedger()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        shiftBodyParagraphPathsOneBased(output);
        List<String> firstClaimEvidence = jsonTextValues(
                output.at("/claims/6/evidenceIds"));
        List<String> secondClaimEvidence = jsonTextValues(
                output.at("/claims/7/evidenceIds"));

        GeneratedApplicationDocuments accepted = parse(output);
        GeneratedClaim firstParagraphClaim =
                claimById(accepted, "CLAIM-007");
        GeneratedClaim remainingParagraphClaim =
                claimById(accepted, "CLAIM-008");

        assertEquals(
                List.of("/coverLetter/bodyParagraphs/0"),
                firstParagraphClaim.getContentPaths());
        assertEquals(
                List.of(
                        "/coverLetter/bodyParagraphs/1",
                        "/coverLetter/bodyParagraphs/2"),
                remainingParagraphClaim.getContentPaths());
        assertEquals(
                firstClaimEvidence,
                firstParagraphClaim.getEvidenceIds());
        assertEquals(
                secondClaimEvidence,
                remainingParagraphClaim.getEvidenceIds());
    }

    @Test
    void normalizesACompleteFiveParagraphOneBasedLedger()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        ArrayNode paragraphs =
                (ArrayNode) output.at("/coverLetter/bodyParagraphs");
        paragraphs.add("I contribute clear communication.");
        paragraphs.add("I focus on dependable delivery.");
        ((ArrayNode) output.at("/claims/7/contentPaths"))
                .add("/coverLetter/bodyParagraphs/3")
                .add("/coverLetter/bodyParagraphs/4");
        shiftBodyParagraphPathsOneBased(output);

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                List.of(
                        "/coverLetter/bodyParagraphs/0",
                        "/coverLetter/bodyParagraphs/1",
                        "/coverLetter/bodyParagraphs/2",
                        "/coverLetter/bodyParagraphs/3",
                        "/coverLetter/bodyParagraphs/4"),
                accepted.getClaims().stream()
                        .flatMap(claim ->
                                claim.getContentPaths().stream())
                        .filter(path -> path.startsWith(
                                "/coverLetter/bodyParagraphs/"))
                        .sorted()
                        .toList());
    }

    @Test
    void repairsGroundedBodyParagraphCoverageButRejectsInvalidPointers()
            throws Exception {
        useVersionedCatalog();

        ObjectNode partial = versionedOutput();
        shiftBodyParagraphPathsOneBased(partial);
        removeContentPath(
                partial,
                7,
                "/coverLetter/bodyParagraphs/3");
        parse(partial);

        ObjectNode duplicate = versionedOutput();
        shiftBodyParagraphPathsOneBased(duplicate);
        ((ArrayNode) duplicate.at("/claims/7/contentPaths"))
                .set(
                        1,
                        objectMapper.getNodeFactory().textNode(
                                "/coverLetter/bodyParagraphs/2"));
        parse(duplicate);

        ObjectNode mixed = versionedOutput();
        shiftBodyParagraphPathsOneBased(mixed);
        ((ArrayNode) mixed.at("/claims/6/contentPaths"))
                .set(
                        0,
                        objectMapper.getNodeFactory().textNode(
                                "/coverLetter/bodyParagraphs/0"));
        assertRejected(
                mixed,
                "content path is not an approved final claim path");

        ObjectNode containerAndLeaf = versionedOutput();
        ((ArrayNode) containerAndLeaf.at(
                        "/claims/6/contentPaths"))
                .set(
                        0,
                        objectMapper.getNodeFactory().textNode(
                                "/coverLetter/bodyParagraphs"));
        assertRejected(
                containerAndLeaf,
                "body paragraph claims mix container and leaf paths");
    }

    @Test
    void rejectsMalformedOneBasedBodyParagraphPointers()
            throws Exception {
        useVersionedCatalog();

        for (String malformedPath : List.of(
                "/coverLetter/bodyParagraphs/4",
                "/coverLetter/bodyParagraphs/01",
                "/coverLetter/bodyParagraphs/1/text")) {
            ObjectNode output = versionedOutput();
            shiftBodyParagraphPathsOneBased(output);
            ((ArrayNode) output.at("/claims/6/contentPaths"))
                    .set(
                            0,
                            objectMapper.getNodeFactory().textNode(
                                    malformedPath));
            assertRejected(
                    output,
                    "content path is not an approved final claim path");
        }
    }

    @Test
    void rejectsReviewOnlyBodyParagraphPointersWithoutRepairingThem()
            throws Exception {
        useVersionedCatalog();
        ObjectNode zeroBased = versionedOutput();
        ObjectNode review = reviewOnlyClaim(
                "CLAIM-011",
                "CONFIRMATION_REQUIRED",
                "Please confirm this paragraph.");
        review.withArray("contentPaths")
                .add("/coverLetter/bodyParagraphs/0");
        ((ArrayNode) zeroBased.path("claims")).add(review);

        assertRejected(
                zeroBased,
                "review-only claim points at final content");

        ObjectNode oneBased = versionedOutput();
        shiftBodyParagraphPathsOneBased(oneBased);
        ObjectNode paragraphOwner =
                (ObjectNode) oneBased.at("/claims/6");
        paragraphOwner.put(
                "disposition",
                "CONFIRMATION_REQUIRED");
        paragraphOwner.put(
                "reviewText",
                "Please confirm this paragraph.");

        assertRejected(
                oneBased,
                "content path is not an approved final claim path");
    }

    @Test
    void leavesValidZeroBasedAndContainerOnlyLedgersUnchanged()
            throws Exception {
        useVersionedCatalog();
        ObjectNode zeroBased = versionedOutput();
        GeneratedApplicationDocuments zeroBasedAccepted =
                parse(zeroBased);
        assertEquals(
                List.of("/coverLetter/bodyParagraphs/0"),
                claimById(
                                zeroBasedAccepted,
                                "CLAIM-007")
                        .getContentPaths());

        ObjectNode containerOnly = versionedOutput();
        ArrayNode claims =
                (ArrayNode) containerOnly.path("claims");
        ObjectNode owner = (ObjectNode) claims.get(6);
        owner.withArray("contentPaths")
                .removeAll()
                .add("/coverLetter/bodyParagraphs");
        claims.remove(7);

        GeneratedApplicationDocuments containerAccepted =
                parse(containerOnly);

        assertEquals(
                List.of(
                        "/coverLetter/bodyParagraphs/0",
                        "/coverLetter/bodyParagraphs/1",
                        "/coverLetter/bodyParagraphs/2"),
                claimById(
                                containerAccepted,
                                "CLAIM-007")
                        .getContentPaths());
    }

    @Test
    void doesNotApplyOneBasedRecoveryToLegacyEvidence()
            throws Exception {
        ObjectNode output = validOutput();
        shiftBodyParagraphPathsOneBased(output);

        assertRejected(
                output,
                "content path is not an approved final claim path");
    }

    @Test
    void rejectsInvalidEvidenceAfterOneBasedParagraphRecovery()
            throws Exception {
        useVersionedCatalog();

        ObjectNode unknown = versionedOutput();
        shiftBodyParagraphPathsOneBased(unknown);
        ((ArrayNode) unknown.at("/claims/6/evidenceIds"))
                .add("UNKNOWN.EVIDENCE");
        assertRejected(unknown, "evidence ID is not approved");

        ObjectNode duplicate = versionedOutput();
        shiftBodyParagraphPathsOneBased(duplicate);
        ArrayNode duplicateEvidence =
                (ArrayNode) duplicate.at("/claims/6/evidenceIds");
        duplicateEvidence.add(duplicateEvidence.get(0).asText());
        assertRejected(duplicate, "evidence ID is duplicated");

        ObjectNode wrongPurpose = versionedOutput();
        shiftBodyParagraphPathsOneBased(wrongPurpose);
        ((ArrayNode) wrongPurpose.at("/claims/6/evidenceIds"))
                .removeAll()
                .add(com.jobseekercopilot.cvcoverletter
                        .GenerationInputFixtures
                        .CV_SKILL_FACT_ID
                        .toString());
        assertRejected(
                wrongPurpose,
                "evidence ID is not approved for this document purpose");

        ObjectNode unsupported = versionedOutput();
        shiftBodyParagraphPathsOneBased(unsupported);
        ((ArrayNode) unsupported.at(
                        "/coverLetter/bodyParagraphs"))
                .set(
                        0,
                        objectMapper.getNodeFactory().textNode(
                                "I improved delivery by 99%."));
        assertRejected(
                unsupported,
                "numeric claim is absent from approved evidence");
    }

    @Test
    void doesNotNormalizeOneBasedPointersForOtherArrays()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        replaceContentPath(
                output,
                1,
                "/cv/projects/0/title",
                "/cv/projects/1/title");

        assertRejected(
                output,
                "content path is not an approved final claim path");
    }

    @Test
    void normalizesACompleteVersionedOneBasedProjectHighlightLedger()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        addProjectHighlights(output, 2);
        shiftProjectHighlightPathsOneBased(output);

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                java.util.stream.IntStream.range(0, 2)
                        .mapToObj(index ->
                                "/cv/projects/0/highlights/" + index)
                        .toList(),
                accepted.getClaims().stream()
                        .flatMap(claim ->
                                claim.getContentPaths().stream())
                        .filter(path -> path.startsWith(
                                "/cv/projects/0/highlights/"))
                        .sorted()
                        .toList());
    }

    @Test
    void normalizesDuplicateOwnershipOfACompleteOneBasedHighlightLedger()
            throws Exception {
        useVersionedCatalog();
        ObjectNode output = versionedOutput();
        addProjectHighlights(output, 2);
        shiftProjectHighlightPathsOneBased(output);
        ((ArrayNode) output.at("/claims/1/contentPaths"))
                .add("/cv/projects/0/highlights/1");

        GeneratedApplicationDocuments accepted = parse(output);

        assertEquals(
                java.util.stream.IntStream.range(0, 2)
                        .mapToObj(index ->
                                "/cv/projects/0/highlights/" + index)
                        .toList(),
                accepted.getClaims().stream()
                        .flatMap(claim -> claim.getContentPaths().stream())
                        .filter(path -> path.startsWith(
                                "/cv/projects/0/highlights/"))
                        .sorted()
                        .toList());
    }

    @Test
    void rejectsIncompleteOrMixedOneBasedProjectHighlightLedgers()
            throws Exception {
        useVersionedCatalog();

        ObjectNode incomplete = versionedOutput();
        addProjectHighlights(incomplete, 2);
        shiftProjectHighlightPathsOneBased(incomplete);
        removeContentPath(
                incomplete,
                1,
                "/cv/projects/0/highlights/1");
        assertRejected(
                incomplete,
                "content path is not an approved final claim path");

        ObjectNode mixed = versionedOutput();
        addProjectHighlights(mixed, 2);
        shiftProjectHighlightPathsOneBased(mixed);
        replaceContentPath(
                mixed,
                1,
                "/cv/projects/0/highlights/1",
                "/cv/projects/0/highlights/0");
        assertRejected(
                mixed,
                "content path is not an approved final claim path");
    }

    @Test
    void normalizesACompleteVersionedOneBasedWorkResponsibilityLedger()
            throws Exception {
        ObjectNode output = validOutput();
        addVersionedWorkHistoryWithResponsibilities(output, 2);
        shiftWorkResponsibilityPathsOneBased(output);

        List<GeneratedClaim> accepted = normalizeOneBasedNestedTextArrays(
                output);

        assertEquals(
                List.of(
                        "/cv/workHistory/0/responsibilities/0",
                        "/cv/workHistory/0/responsibilities/1"),
                accepted.stream()
                        .flatMap(claim -> claim.getContentPaths().stream())
                        .filter(path -> path.startsWith(
                                "/cv/workHistory/0/responsibilities/"))
                        .sorted()
                        .toList());
    }

    @Test
    void rejectsIncompleteOrMixedOneBasedWorkResponsibilityLedgers()
            throws Exception {
        ObjectNode incomplete = validOutput();
        addVersionedWorkHistoryWithResponsibilities(incomplete, 2);
        shiftWorkResponsibilityPathsOneBased(incomplete);
        removeContentPath(
                incomplete,
                10,
                "/cv/workHistory/0/responsibilities/1");
        assertEquals(
                List.of("/cv/workHistory/0/responsibilities/2"),
                responsibilityPaths(normalizeOneBasedNestedTextArrays(
                        incomplete)));

        ObjectNode mixed = validOutput();
        addVersionedWorkHistoryWithResponsibilities(mixed, 2);
        shiftWorkResponsibilityPathsOneBased(mixed);
        replaceContentPath(
                mixed,
                10,
                "/cv/workHistory/0/responsibilities/1",
                "/cv/workHistory/0/responsibilities/0");
        assertEquals(
                List.of(
                        "/cv/workHistory/0/responsibilities/0",
                        "/cv/workHistory/0/responsibilities/2"),
                responsibilityPaths(normalizeOneBasedNestedTextArrays(mixed)));
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
        return parser.parse(
                objectMapper.writeValueAsString(output),
                schema,
                catalog);
    }

    private List<GeneratedClaim> normalizeOneBasedNestedTextArrays(
            ObjectNode output
    ) throws Exception {
        GeneratedApplicationDocuments documents = objectMapper.treeToValue(
                output,
                GeneratedApplicationDocuments.class);
        return new ClaimEvidenceValidator()
                .normalizeCompleteOneBasedNestedTextArrayPaths(
                        output,
                        documents.getClaims(),
                        true);
    }

    private List<String> responsibilityPaths(List<GeneratedClaim> claims) {
        return claims.stream()
                .flatMap(claim -> claim.getContentPaths().stream())
                .filter(path -> path.startsWith(
                        "/cv/workHistory/0/responsibilities/"))
                .sorted()
                .toList();
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

    private void useSchemaFixture(
            String releaseId
    ) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/"
                        + releaseId
                        + "/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException(
                        "Output schema fixture is missing for "
                                + releaseId
                                + ".");
            }
            schema = objectMapper.readTree(input);
        }
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

    private ObjectNode activeVersionedOutput() throws Exception {
        ObjectNode output = versionedOutput();
        for (int claimIndex : List.of(5, 8)) {
            ObjectNode claim =
                    (ObjectNode) output.at(
                            "/claims/" + claimIndex);
            claim.put("disposition", "SUPPORTED");
            claim.withArray("evidenceIds")
                    .removeAll()
                    .add("REQUEST.GENERATION_INTENT")
                    .add("JOB.TITLE")
                    .add("JOB.COMPANY");
        }
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

    private void replaceContentPath(
            ObjectNode output,
            int claimIndex,
            String existingPath,
            String replacementPath
    ) {
        ArrayNode contentPaths =
                (ArrayNode) output.at(
                        "/claims/" + claimIndex + "/contentPaths");
        for (int index = 0;
                index < contentPaths.size();
                index++) {
            if (contentPaths.get(index)
                    .asText()
                    .equals(existingPath)) {
                contentPaths.set(
                        index,
                        objectMapper.getNodeFactory()
                                .textNode(replacementPath));
            }
        }
    }

    private void shiftBodyParagraphPathsOneBased(
            ObjectNode output
    ) {
        int paragraphCount =
                output.at("/coverLetter/bodyParagraphs").size();
        java.util.Map<String, String> shifts =
                new java.util.LinkedHashMap<>();
        for (int index = 0;
                index < paragraphCount;
                index++) {
            shifts.put(
                    "/coverLetter/bodyParagraphs/" + index,
                    "/coverLetter/bodyParagraphs/" + (index + 1));
        }
        for (JsonNode claim : output.path("claims")) {
            ArrayNode contentPaths =
                    (ArrayNode) claim.path("contentPaths");
            for (int index = 0;
                    index < contentPaths.size();
                    index++) {
                String shifted =
                        shifts.get(contentPaths.get(index).asText());
                if (shifted != null) {
                    contentPaths.set(
                            index,
                            objectMapper.getNodeFactory()
                                    .textNode(shifted));
                }
            }
        }
    }

    private void addProjectHighlights(
            ObjectNode output,
            int count
    ) {
        ArrayNode highlights =
                (ArrayNode) output.at("/cv/projects/0/highlights");
        ArrayNode contentPaths =
                (ArrayNode) output.at("/claims/1/contentPaths");
        for (int index = 0; index < count; index++) {
            highlights.add(index == 0
                    ? "Applied Java skills to useful services."
                    : "Developed useful services with Java.");
            contentPaths.add(
                    "/cv/projects/0/highlights/" + index);
        }
    }

    private void shiftProjectHighlightPathsOneBased(
            ObjectNode output
    ) {
        int highlightCount =
                output.at("/cv/projects/0/highlights").size();
        java.util.Map<String, String> shifts =
                new java.util.LinkedHashMap<>();
        for (int index = 0; index < highlightCount; index++) {
            shifts.put(
                    "/cv/projects/0/highlights/" + index,
                    "/cv/projects/0/highlights/" + (index + 1));
        }
        for (JsonNode claim : output.path("claims")) {
            ArrayNode contentPaths =
                    (ArrayNode) claim.path("contentPaths");
            for (int index = 0;
                    index < contentPaths.size();
                    index++) {
                String shifted =
                        shifts.get(contentPaths.get(index).asText());
                if (shifted != null) {
                    contentPaths.set(
                            index,
                            objectMapper.getNodeFactory()
                                    .textNode(shifted));
                }
            }
        }
    }

    private void addVersionedWorkHistoryWithResponsibilities(
            ObjectNode output,
            int responsibilityCount
    ) {
        ObjectNode history = objectMapper.createObjectNode();
        history.put("jobTitle", "Software Engineer");
        history.put("employer", "Example Ltd");
        history.put("startDate", "2022-03");
        history.put("endDate", "");
        ArrayNode responsibilities = history.putArray("responsibilities");
        history.put("tailoredDescription", "");
        ((ArrayNode) output.at("/cv/workHistory")).add(history);

        ObjectNode claim = objectMapper.createObjectNode();
        claim.put("claimId", "CLAIM-011");
        claim.put("disposition", "SUPPORTED");
        claim.putArray("evidenceIds");
        claim.putArray("contentPaths")
                .add("/cv/workHistory/0/jobTitle")
                .add("/cv/workHistory/0/employer")
                .add("/cv/workHistory/0/startDate");
        claim.put("reviewText", "");
        ((ArrayNode) output.path("claims")).add(claim);

        for (int index = 0; index < responsibilityCount; index++) {
            String value = index == 0
                    ? "Built useful Java services."
                    : "Maintained reliable Java services.";
            responsibilities.add(value);
            claim.withArray("contentPaths").add(
                    "/cv/workHistory/0/responsibilities/" + index);
        }
    }

    private void shiftWorkResponsibilityPathsOneBased(
            ObjectNode output
    ) {
        int responsibilityCount =
                output.at("/cv/workHistory/0/responsibilities").size();
        java.util.Map<String, String> shifts =
                new java.util.LinkedHashMap<>();
        for (int index = 0; index < responsibilityCount; index++) {
            shifts.put(
                    "/cv/workHistory/0/responsibilities/" + index,
                    "/cv/workHistory/0/responsibilities/" + (index + 1));
        }
        for (JsonNode claim : output.path("claims")) {
            ArrayNode contentPaths = (ArrayNode) claim.path("contentPaths");
            for (int index = 0; index < contentPaths.size(); index++) {
                String shifted = shifts.get(contentPaths.get(index).asText());
                if (shifted != null) {
                    contentPaths.set(
                            index,
                            objectMapper.getNodeFactory().textNode(shifted));
                }
            }
        }
    }

    private List<String> jsonTextValues(JsonNode values) {
        java.util.ArrayList<String> result =
                new java.util.ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private GeneratedClaim claimById(
            GeneratedApplicationDocuments documents,
            String claimId
    ) {
        return documents.getClaims().stream()
                .filter(claim ->
                        claimId.equals(claim.getClaimId()))
                .findFirst()
                .orElseThrow();
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
