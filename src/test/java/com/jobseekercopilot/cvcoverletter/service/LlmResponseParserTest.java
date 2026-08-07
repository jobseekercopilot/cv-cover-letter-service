package com.jobseekercopilot.cvcoverletter.service;

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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmResponseParserTest {

    private ObjectMapper objectMapper;
    private LlmResponseParser parser;
    private JsonNode schema;
    private JsonNode inlineNarrativeSchema;
    private JsonNode coreSkillProjectionRollbackSchema;
    private JsonNode dedicatedRollbackSchema;
    private JsonNode legacySchema;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        parser = new LlmResponseParser(
                objectMapper,
                new ClaimEvidenceValidator(),
                new GeneratedDocumentQualityValidator());
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.5.9/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Active output schema fixture is missing.");
            }
            schema = objectMapper.readTree(input);
        }
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.5.10/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException(
                        "Inline narrative output schema fixture is missing.");
            }
            inlineNarrativeSchema = objectMapper.readTree(input);
        }
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.5.6/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException(
                        "Core-skill projection rollback schema fixture is missing.");
            }
            coreSkillProjectionRollbackSchema = objectMapper.readTree(input);
        }
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.5.5/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException(
                        "Dedicated rollback output schema fixture is missing.");
            }
            dedicatedRollbackSchema = objectMapper.readTree(input);
        }
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.5.3/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Rollback output schema fixture is missing.");
            }
            legacySchema = objectMapper.readTree(input);
        }
    }

    @Test
    void parsesJsonThatMatchesTheExactProviderSchema() {
        GeneratedApplicationDocuments result =
                parser.parse(CvCoverLetterServiceTest.activeValidJson(), schema);

        assertEquals("Tailored Developer CV", result.getCv().getTitle());
        assertEquals("Developer Cover Letter", result.getCoverLetter().getTitle());
        assertEquals("Focused on Java.", result.getGenerationNotes().getTailoringSummary());
        assertEquals(8, result.getClaims().size());
        assertEquals(1, result.getClaims().stream()
                .filter(claim -> "CLAIM-9001".equals(claim.getClaimId()))
                .count());
        assertEquals(1, result.getClaims().stream()
                .filter(claim -> "CLAIM-9002".equals(claim.getClaimId()))
                .count());
        assertEquals(1, result.getClaims().stream()
                .filter(claim -> "CLAIM-9003".equals(claim.getClaimId()))
                .filter(claim -> List.of("/cv/personalSummary")
                        .equals(claim.getContentPaths()))
                .count());
        assertEquals("3.5.2", parser.parserVersion(schema));
        assertEquals("2.14.0", parser.claimPolicyVersion(schema));
    }

    @Test
    void projectsEveryInlineNarrativeItemIntoBoundedClaims() throws Exception {
        JsonNode output = inlineNarrativeOutput();

        GeneratedApplicationDocuments result = parser.parse(
                objectMapper.writeValueAsString(output),
                inlineNarrativeSchema);

        assertEquals("3.6.0", parser.parserVersion(inlineNarrativeSchema));
        assertEquals("2.14.0", parser.claimPolicyVersion(inlineNarrativeSchema));
        assertEquals(
                List.of("Delivered a reliable service."),
                result.getCv().getProjects().get(0).getHighlights());
        assertEquals(
                List.of("Built and maintained Java services."),
                result.getCv().getWorkHistory().get(0).getResponsibilities());
        assertEquals(
                List.of(
                        "My experience is a strong match.",
                        "I build useful services.",
                        "The role calls for useful services."),
                result.getCoverLetter().getBodyParagraphs());
        List.of(
                        "/cv/projects/0/highlights/0",
                        "/cv/workHistory/0/responsibilities/0",
                        "/coverLetter/bodyParagraphs/0",
                        "/coverLetter/bodyParagraphs/1",
                        "/coverLetter/bodyParagraphs/2")
                .forEach(path -> assertEquals(
                        1,
                        result.getClaims().stream()
                                .filter(claim -> claim.getContentPaths()
                                        .contains(path))
                                .count(),
                        path));
    }

    @Test
    void rejectsInlineNarrativeWithoutEvidenceBeforeProjection()
            throws Exception {
        JsonNode output = inlineNarrativeOutput();
        ((ArrayNode) output.at(
                "/coverLetter/bodyParagraphs/1/evidenceIds"))
                .removeAll();

        InvalidLlmResponseException error = assertThrows(
                InvalidLlmResponseException.class,
                () -> parser.parse(
                        objectMapper.writeValueAsString(output),
                        inlineNarrativeSchema));

        assertTrue(error.getMessage().contains(
                "$.coverLetter.bodyParagraphs[1].evidenceIds"));
    }

    @Test
    void rejectsReviewOnlyOrStructurallyEmptyClaimsAtTheActiveSchemaBoundary()
            throws Exception {
        for (String disposition :
                new String[] {"CONFIRMATION_REQUIRED", "REJECTED"}) {
            JsonNode reviewOnly =
                    objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
            ((ObjectNode) reviewOnly.at("/claims/0"))
                    .put("disposition", disposition);
            assertRejectedAt(reviewOnly, "$.claims[0].disposition");
        }

        JsonNode emptyEvidence =
                objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
        ((ArrayNode) emptyEvidence.at("/claims/0/evidenceIds")).removeAll();
        assertRejectedAt(emptyEvidence, "$.claims[0].evidenceIds");

        JsonNode emptyPaths =
                objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
        ((ArrayNode) emptyPaths.at("/claims/0/contentPaths")).removeAll();
        assertRejectedAt(emptyPaths, "$.claims[0].contentPaths");

        JsonNode reviewText =
                objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) reviewText.at("/claims/0"))
                .put("reviewText", "Please confirm this claim.");
        assertRejectedAt(reviewText, "$.claims[0].reviewText");

        JsonNode terminalLineSeparator =
                objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) terminalLineSeparator.at("/claims/0"))
                .put("reviewText", "\n");
        assertRejectedAt(terminalLineSeparator, "$.claims[0].reviewText");
    }

    @Test
    void requiresCoreSkillEvidenceToBeExactlyEmptyAtTheActiveSchemaBoundary()
            throws Exception {
        JsonNode accepted = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ObjectNode skill = objectMapper.createObjectNode();
        skill.put("name", "Java");
        skill.put("evidence", "");
        ((ArrayNode) accepted.at("/cv/coreSkills")).add(skill);

        GeneratedApplicationDocuments documents = parser.parse(
                objectMapper.writeValueAsString(accepted), schema);
        assertEquals(1, documents.getCv().getCoreSkills().size());
        assertEquals("", documents.getCv().getCoreSkills().get(0).getEvidence());

        for (String forbidden : new String[] {
                "Built useful Java services.",
                " ",
                "\n"
        }) {
            JsonNode nonEmpty = accepted.deepCopy();
            ((ObjectNode) nonEmpty.at("/cv/coreSkills/0"))
                    .put("evidence", forbidden);
            assertRejectedAt(nonEmpty, "$.cv.coreSkills[0].evidence");
        }

        JsonNode missing = accepted.deepCopy();
        ((ObjectNode) missing.at("/cv/coreSkills/0")).remove("evidence");
        assertRejectedAt(missing, "$.cv.coreSkills[0].evidence");

        JsonNode nullEvidence = accepted.deepCopy();
        ((ObjectNode) nullEvidence.at("/cv/coreSkills/0"))
                .putNull("evidence");
        assertRejectedAt(nullEvidence, "$.cv.coreSkills[0].evidence");
    }

    @Test
    void deterministicallyProjectsLegacySkillsAndRegeneratesExactProvenance()
            throws Exception {
        JsonNode output = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ArrayNode skills = (ArrayNode) output.at("/cv/coreSkills");
        for (String name : List.of("Spring", "Kubernetes", "Spring")) {
            ObjectNode skill = skills.addObject();
            skill.put("name", name);
            skill.put("evidence", "");
        }
        ClaimEvidenceCatalog catalog = new ClaimEvidenceCatalogFactory()
                .create(new GenerationInputNormalizer().normalize(
                        "owner-123",
                        com.jobseekercopilot.cvcoverletter
                                .GenerationInputFixtures.validRequest()));

        GeneratedApplicationDocuments documents = parser.parse(
                objectMapper.writeValueAsString(output),
                schema,
                catalog);

        assertEquals(
                List.of("Spring", "Java"),
                documents.getCv().getCoreSkills().stream()
                        .map(skill -> skill.getName())
                        .toList());
        assertTrue(documents.getCv().getCoreSkills().stream()
                .allMatch(skill -> skill.getEvidence().isEmpty()));
        assertEquals(
                List.of("PROFILE.SKILL.2"),
                claimFor(documents, "/cv/coreSkills/0/name")
                        .getEvidenceIds());
        assertEquals(
                List.of("PROFILE.SKILL.1"),
                claimFor(documents, "/cv/coreSkills/1/name")
                        .getEvidenceIds());
    }

    @Test
    void rejectsNonCanonicalApplicationBookendsAtTheActiveSchemaBoundary()
            throws Exception {
        for (String opening : new String[] {
                "I am keen to apply for this role.",
                "Please consider my application for this role. ",
                "Please consider my application for this role.\n"
        }) {
            JsonNode output =
                    objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
            ((ObjectNode) output.path("coverLetter"))
                    .put("openingParagraph", opening);
            assertRejectedAt(output, "$.coverLetter.openingParagraph");
        }

        for (String closing : new String[] {
                "Thank you for considering my application at Example Ltd.",
                "Thank you for considering my application. ",
                "Thank you for considering my application.\n"
        }) {
            JsonNode output =
                    objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
            ((ObjectNode) output.path("coverLetter"))
                    .put("closingParagraph", closing);
            assertRejectedAt(output, "$.coverLetter.closingParagraph");
        }
    }

    @Test
    void rejectsMissingWrongOrSurplusDedicatedCanonicalClaimsBeforeProjection()
            throws Exception {
        JsonNode missingOpening = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) missingOpening.path("canonicalApplicationClaims"))
                .remove("opening");
        assertRejectedAt(
                missingOpening,
                "$.canonicalApplicationClaims.opening");

        JsonNode wrongClaimId = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) wrongClaimId.at("/canonicalApplicationClaims/opening"))
                .put("claimId", "CLAIM-9002");
        assertRejectedAt(
                wrongClaimId,
                "$.canonicalApplicationClaims.opening.claimId");

        JsonNode wrongDisposition = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) wrongDisposition.at("/canonicalApplicationClaims/opening"))
                .put("disposition", "REWORDED");
        assertRejectedAt(
                wrongDisposition,
                "$.canonicalApplicationClaims.opening.disposition");

        JsonNode wrongEvidence = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) wrongEvidence.at("/canonicalApplicationClaims/opening"))
                .put("companyEvidenceId", "JOB.TITLE");
        assertRejectedAt(
                wrongEvidence,
                "$.canonicalApplicationClaims.opening.companyEvidenceId");

        JsonNode wrongPath = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) wrongPath.at("/canonicalApplicationClaims/closing"))
                .put("contentPath", "/coverLetter/openingParagraph");
        assertRejectedAt(
                wrongPath,
                "$.canonicalApplicationClaims.closing.contentPath");

        JsonNode nonEmptyReview = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) nonEmptyReview.at("/canonicalApplicationClaims/closing"))
                .put("reviewText", "review");
        assertRejectedAt(
                nonEmptyReview,
                "$.canonicalApplicationClaims.closing.reviewText");

        JsonNode surplusEvidence = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) surplusEvidence.at("/canonicalApplicationClaims/opening"))
                .put("extraEvidenceId", "JOB.DESCRIPTION");
        assertRejectedAt(
                surplusEvidence,
                "$.canonicalApplicationClaims.opening.extraEvidenceId");
    }

    @Test
    void rejectsMissingWrongOrSurplusDedicatedPersonalSummaryClaim()
            throws Exception {
        JsonNode missing = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) missing).remove("personalSummaryClaim");
        assertRejectedAt(missing, "$.personalSummaryClaim");

        JsonNode wrongId = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) wrongId.path("personalSummaryClaim"))
                .put("claimId", "CLAIM-9002");
        assertRejectedAt(wrongId, "$.personalSummaryClaim.claimId");

        JsonNode wrongDisposition = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) wrongDisposition.path("personalSummaryClaim"))
                .put("disposition", "REJECTED");
        assertRejectedAt(
                wrongDisposition,
                "$.personalSummaryClaim.disposition");

        JsonNode emptyEvidence = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ArrayNode) emptyEvidence.at(
                "/personalSummaryClaim/evidenceIds")).removeAll();
        assertRejectedAt(
                emptyEvidence,
                "$.personalSummaryClaim.evidenceIds");

        JsonNode excessiveEvidence = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ArrayNode excessiveEvidenceIds = (ArrayNode) excessiveEvidence.at(
                "/personalSummaryClaim/evidenceIds");
        while (excessiveEvidenceIds.size() < 31) {
            excessiveEvidenceIds.add(
                    "PROFILE.SKILL." + (excessiveEvidenceIds.size() + 10));
        }
        assertRejectedAt(
                excessiveEvidence,
                "$.personalSummaryClaim.evidenceIds");

        JsonNode wrongPath = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) wrongPath.path("personalSummaryClaim"))
                .put("contentPath", "/cv/title");
        assertRejectedAt(wrongPath, "$.personalSummaryClaim.contentPath");

        JsonNode nonEmptyReview = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) nonEmptyReview.path("personalSummaryClaim"))
                .put("reviewText", "review required");
        assertRejectedAt(
                nonEmptyReview,
                "$.personalSummaryClaim.reviewText");

        JsonNode surplus = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) surplus.path("personalSummaryClaim"))
                .put("extra", "not permitted");
        assertRejectedAt(surplus, "$.personalSummaryClaim.extra");
    }

    @Test
    void ordinaryClaimsCannotUseReservedIdsOrCanonicalBookendPaths()
            throws Exception {
        JsonNode reservedId = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) reservedId.at("/claims/0"))
                .put("claimId", "CLAIM-9001");
        assertRejectedAt(reservedId, "$.claims[0].claimId");

        for (String path : new String[] {
                "/coverLetter/openingParagraph",
                "/coverLetter/closingParagraph",
                "/cv/title",
                "/cv/personalSummary",
                "/coverLetter/title"
        }) {
            JsonNode groupedBookend = objectMapper.readTree(
                    CvCoverLetterServiceTest.activeValidJson());
            ((ArrayNode) groupedBookend.at("/claims/0/contentPaths"))
                    .set(0, objectMapper.getNodeFactory().textNode(path));
            assertRejectedAt(groupedBookend, "$.claims[0].contentPaths[0]");
        }
    }

    @Test
    void ordinaryClaimPathsAreRestrictedToExactClaimBearingLeaves() {
        String pattern = schema.at(
                "/properties/claims/items/properties/contentPaths/items/pattern")
                .asText();

        for (String allowed : new String[] {
                "/cv/targetRole",
                "/cv/projects/0/title",
                "/cv/projects/12/highlights/7",
                "/cv/qualifications/0/qualificationName",
                "/cv/qualifications/19/expectedCompletion",
                "/cv/workHistory/0/jobTitle",
                "/cv/workHistory/19/responsibilities/11",
                "/coverLetter/jobTitle",
                "/coverLetter/companyName",
                "/coverLetter/bodyParagraphs/4"
        }) {
            assertTrue(allowed.matches(pattern), allowed);
        }
        for (String rejected : new String[] {
                "/cv/title",
                "/cv/personalSummary",
                "/cv/coreSkills",
                "/cv/coreSkills/0/name",
                "/cv/coreSkills/0/evidence",
                "/cv/projects/0",
                "/cv/projects/0/highlights",
                "/cv/qualifications/0/qualificationTitle",
                "/cv/workHistory/0/responsibilities",
                "/coverLetter/greeting",
                "/coverLetter/title",
                "/coverLetter/openingParagraph",
                "/coverLetter/closingParagraph",
                "/coverLetter/signOff",
                "/generationNotes/tailoringSummary"
        }) {
            assertFalse(rejected.matches(pattern), rejected);
        }
    }

    @Test
    void reservesCapacityForCanonicalAndProjectedSkillClaims()
            throws Exception {
        JsonNode bounded = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ArrayNode claims = (ArrayNode) bounded.path("claims");
        while (claims.size() < 29) {
            ObjectNode copy = claims.get(0).deepCopy();
            copy.put("claimId", "CLAIM-" + (100 + claims.size()));
            claims.add(copy);
        }

        GeneratedApplicationDocuments accepted = parser.parse(
                objectMapper.writeValueAsString(bounded), schema);
        assertEquals(32, accepted.getClaims().size());

        ObjectNode extra = claims.get(0).deepCopy();
        extra.put("claimId", "CLAIM-888");
        claims.add(extra);
        assertRejectedAt(bounded, "$.claims");

        JsonNode projectionRollbackBounded = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) projectionRollbackBounded)
                .remove("personalSummaryClaim");
        ArrayNode projectionRollbackClaims =
                (ArrayNode) projectionRollbackBounded.path("claims");
        ObjectNode summaryClaim = projectionRollbackClaims.addObject();
        summaryClaim.put("claimId", "CLAIM-002");
        summaryClaim.put("disposition", "REWORDED");
        summaryClaim.putArray("evidenceIds")
                .add("PROFILE.SKILL.1")
                .add("JOB.DESCRIPTION");
        summaryClaim.putArray("contentPaths")
                .add("/cv/personalSummary");
        summaryClaim.put("reviewText", "");
        while (projectionRollbackClaims.size() < 26) {
            ObjectNode copy = projectionRollbackClaims.get(0).deepCopy();
            copy.put("claimId", "CLAIM-" + (100
                    + projectionRollbackClaims.size()));
            projectionRollbackClaims.add(copy);
        }
        GeneratedApplicationDocuments projectionRollbackAccepted =
                parser.parse(
                        objectMapper.writeValueAsString(
                                projectionRollbackBounded),
                        coreSkillProjectionRollbackSchema);
        assertEquals(28, projectionRollbackAccepted.getClaims().size());

        JsonNode rollbackBounded = projectionRollbackBounded.deepCopy();
        ArrayNode rollbackClaims =
                (ArrayNode) rollbackBounded.path("claims");
        while (rollbackClaims.size() < 38) {
            ObjectNode copy = rollbackClaims.get(0).deepCopy();
            copy.put("claimId", "CLAIM-" + (100 + rollbackClaims.size()));
            rollbackClaims.add(copy);
        }
        GeneratedApplicationDocuments rollbackAccepted = parser.parse(
                objectMapper.writeValueAsString(rollbackBounded),
                dedicatedRollbackSchema);
        assertEquals(40, rollbackAccepted.getClaims().size());

        ObjectNode rollbackExtra = rollbackClaims.get(0).deepCopy();
        rollbackExtra.put("claimId", "CLAIM-888");
        rollbackClaims.add(rollbackExtra);
        assertRejectedAt(
                rollbackBounded,
                dedicatedRollbackSchema,
                "$.claims");
    }

    @Test
    void failsClosedWhenDedicatedSchemaSingletonsOrExclusionsAreWeakened()
            throws Exception {
        JsonNode widenedId = schema.deepCopy();
        ((ArrayNode) widenedId.at(
                "/properties/canonicalApplicationClaims/properties/opening/properties/claimId/enum"))
                .add("CLAIM-9003");
        assertThrows(
                IllegalStateException.class,
                () -> parser.parserVersion(widenedId));

        JsonNode groupedPath = schema.deepCopy();
        ((ObjectNode) groupedPath.at(
                "/properties/claims/items/properties/contentPaths/items"))
                .put("pattern", "^/(cv|coverLetter)(/[A-Za-z0-9_-]+)+$");
        assertThrows(
                IllegalStateException.class,
                () -> parser.parserVersion(groupedPath));

        JsonNode unreservedIds = schema.deepCopy();
        ((ObjectNode) unreservedIds.at(
                "/properties/claims/items/properties/claimId"))
                .put("pattern", "^CLAIM-[0-9]{3,4}$");
        assertThrows(
                IllegalStateException.class,
                () -> parser.parserVersion(unreservedIds));

        JsonNode missingBound = schema.deepCopy();
        ((ObjectNode) missingBound.at("/properties/claims"))
                .remove("maxItems");
        assertThrows(
                IllegalStateException.class,
                () -> parser.parserVersion(missingBound));

        JsonNode nonNumericBound = schema.deepCopy();
        ((ObjectNode) nonNumericBound.at("/properties/claims"))
                .put("maxItems", "29");
        assertThrows(
                IllegalStateException.class,
                () -> parser.parserVersion(nonNumericBound));

        JsonNode excessiveBound = schema.deepCopy();
        ((ObjectNode) excessiveBound.at("/properties/claims"))
                .put("maxItems", 30);
        assertThrows(
                IllegalStateException.class,
                () -> parser.parserVersion(excessiveBound));

        JsonNode missingPersonalSummaryContract = schema.deepCopy();
        ((ObjectNode) missingPersonalSummaryContract.path("properties"))
                .remove("personalSummaryClaim");
        assertThrows(
                IllegalStateException.class,
                () -> parser.parserVersion(
                        missingPersonalSummaryContract));

        JsonNode widenedPersonalSummaryPath = schema.deepCopy();
        ((ObjectNode) widenedPersonalSummaryPath.at(
                "/properties/personalSummaryClaim/properties/contentPath"))
                .put("pattern", "^/cv/.+$");
        assertThrows(
                IllegalStateException.class,
                () -> parser.parserVersion(widenedPersonalSummaryPath));

        JsonNode missingCanonicalContract = schema.deepCopy();
        ((ObjectNode) missingCanonicalContract.path("properties"))
                .remove("canonicalApplicationClaims");
        ArrayNode required = (ArrayNode) missingCanonicalContract.path("required");
        for (int index = required.size() - 1; index >= 0; index--) {
            if ("canonicalApplicationClaims".equals(
                    required.get(index).asText())) {
                required.remove(index);
            }
        }
        assertThrows(
                IllegalStateException.class,
                () -> parser.parserVersion(missingCanonicalContract));
    }

    @Test
    void rejectsMalformedTruncatedFencedTrailingAndDuplicateJsonWithoutEchoingPayload() {
        for (String response : new String[] {
                "response-secret-sentinel",
                CvCoverLetterServiceTest.activeValidJson().substring(0, 80),
                "```json\n" + CvCoverLetterServiceTest.activeValidJson() + "\n```",
                CvCoverLetterServiceTest.activeValidJson() + "{}",
                CvCoverLetterServiceTest.activeValidJson().replace(
                        "\"title\": \"Tailored Developer CV\"",
                        "\"title\": \"Tailored Developer CV\","
                                + "\"title\": \"response-secret-sentinel\"")
        }) {
            InvalidLlmResponseException error = assertThrows(
                    InvalidLlmResponseException.class,
                    () -> parser.parse(response, schema));
            assertFalse(error.getMessage().contains("response-secret-sentinel"));
        }
    }

    @Test
    void rejectsMissingNullUnknownAndWrongTypeFields() throws Exception {
        JsonNode missing = objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) missing.path("cv")).remove("title");
        assertRejectedAt(missing, "$.cv.title");

        JsonNode nullList = objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) nullList.path("cv"))
                .putNull("coreSkills");
        assertRejectedAt(nullList, "$.cv.coreSkills");

        JsonNode unknown = objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) unknown.path("coverLetter"))
                .put("secretField", "response-secret-sentinel");
        InvalidLlmResponseException unknownError = assertRejectedAt(unknown, "$.coverLetter.secretField");
        assertFalse(unknownError.getMessage().contains("response-secret-sentinel"));

        JsonNode wrongType = objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) wrongType.path("cv"))
                .put("personalSummary", 42);
        assertRejectedAt(wrongType, "$.cv.personalSummary");
    }

    @Test
    void rejectsOversizedTextArraysAndWholeResponses() throws Exception {
        JsonNode oversizedTitle = objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) oversizedTitle.path("cv"))
                .put("title", "x".repeat(201));
        assertRejectedAt(oversizedTitle, "$.cv.title");

        JsonNode oversizedArray = objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
        ArrayNode paragraphs = (ArrayNode) oversizedArray.path("coverLetter").path("bodyParagraphs");
        while (paragraphs.size() <= 7) {
            paragraphs.add("Bounded paragraph");
        }
        assertRejectedAt(oversizedArray, "$.coverLetter.bodyParagraphs");

        InvalidLlmResponseException rawLimit = assertThrows(
                InvalidLlmResponseException.class,
                () -> parser.parse(
                        "{\"value\":\"" + "x".repeat(LlmResponseParser.MAX_RAW_RESPONSE_CHARACTERS) + "\"}",
                        schema));
        assertTrue(rawLimit.getMessage().contains("maximum size"));
    }

    @Test
    void keepsApprovedLegacyRollbackSchemasBoundedByParserPolicy() throws Exception {
        JsonNode relaxedLegacySchema = legacySchema.deepCopy();
        removeProviderBounds(relaxedLegacySchema);

        JsonNode oversizedText = objectMapper.readTree(CvCoverLetterServiceTest.validJson());
        ((ObjectNode) oversizedText.path("cv"))
                .put("title", "x".repeat(LlmResponseParser.MAX_FALLBACK_TEXT_CHARACTERS + 1));
        assertRejectedAt(oversizedText, relaxedLegacySchema, "$.cv.title");

        JsonNode oversizedArray = objectMapper.readTree(CvCoverLetterServiceTest.validJson());
        ArrayNode assumptions = (ArrayNode) oversizedArray.path("generationNotes").path("assumptionsMade");
        while (assumptions.size() <= LlmResponseParser.MAX_FALLBACK_ARRAY_ITEMS) {
            assumptions.add("Bounded assumption");
        }
        assertRejectedAt(oversizedArray, relaxedLegacySchema, "$.generationNotes.assumptionsMade");

        GeneratedApplicationDocuments rollback = parser.parse(
                CvCoverLetterServiceTest.validJson(), legacySchema);
        assertEquals(10, rollback.getClaims().size());
        assertEquals("3.2.0", parser.parserVersion(legacySchema));
        assertEquals(
                "3.4.0",
                parser.parserVersion(coreSkillProjectionRollbackSchema));
        assertEquals(
                "2.11.0",
                parser.claimPolicyVersion(
                        coreSkillProjectionRollbackSchema));
        assertEquals(
                "3.3.0",
                parser.parserVersion(dedicatedRollbackSchema));
        assertEquals(
                "2.10.0",
                parser.claimPolicyVersion(dedicatedRollbackSchema));
    }

    @Test
    void rejectsHtmlScriptEncodedMarkupActiveUrisHandlersAndControls() throws Exception {
        for (String payload : new String[] {
                "<script>alert(1)</script>",
                "&lt;iframe src=x&gt;&lt;/iframe&gt;",
                "&amp;lt;iframe src=x&amp;gt;&amp;lt;/iframe&amp;gt;",
                "javascript:alert(1)",
                "data:text/html;base64,PHNjcmlwdD4=",
                "onclick=alert(1)"
        }) {
            String response = CvCoverLetterServiceTest.activeValidJson()
                    .replace("A Java developer focused on useful services.", payload);
            InvalidLlmResponseException error = assertThrows(
                    InvalidLlmResponseException.class,
                    () -> parser.parse(response, schema));
            assertTrue(
                    error.getMessage().contains("$.cv.personalSummary"),
                    payload + " => " + error.getMessage());
            assertFalse(error.getMessage().contains(payload));
        }

        JsonNode control = objectMapper.readTree(CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) control.path("cv"))
                .put("personalSummary", "unsafe\u0000control");
        assertRejectedAt(control, "$.cv.personalSummary");
    }

    private JsonNode inlineNarrativeOutput() throws Exception {
        JsonNode output = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ArrayNode bodyParagraphs = (ArrayNode) output.at(
                "/coverLetter/bodyParagraphs");
        for (int index = 0; index < bodyParagraphs.size(); index++) {
            String text = bodyParagraphs.get(index).asText();
            ObjectNode item = objectMapper.createObjectNode();
            item.put("text", text);
            item.put("disposition", "REWORDED");
            item.putArray("evidenceIds").add("PROFILE.SKILL.1");
            bodyParagraphs.set(index, item);
        }
        ArrayNode claims = (ArrayNode) output.path("claims");
        for (int index = claims.size() - 1; index >= 0; index--) {
            String paths = claims.get(index).path("contentPaths").toString();
            if (paths.contains("/coverLetter/bodyParagraphs/")) {
                claims.remove(index);
            }
        }

        ObjectNode project = ((ArrayNode) output.at("/cv/projects"))
                .addObject();
        project.put("title", "Reliable service");
        project.put("role", "Developer");
        project.put("context", "Portfolio project");
        project.put("startDate", "2026");
        project.put("endDate", "2026");
        project.put("description", "Built a reliable service.");
        ObjectNode highlight = project.putArray("highlights").addObject();
        highlight.put("text", "Delivered a reliable service.");
        highlight.put("disposition", "REWORDED");
        highlight.putArray("evidenceIds").add("PROFILE.SKILL.1");

        ObjectNode employment = ((ArrayNode) output.at("/cv/workHistory"))
                .addObject();
        employment.put("jobTitle", "Developer");
        employment.put("employer", "Example Ltd");
        employment.put("startDate", "2024");
        employment.put("endDate", "2026");
        ObjectNode responsibility = employment.putArray("responsibilities")
                .addObject();
        responsibility.put("text", "Built and maintained Java services.");
        responsibility.put("disposition", "SUPPORTED");
        responsibility.putArray("evidenceIds").add(
                "PROFILE.EMPLOYMENT.1.RESPONSIBILITIES");
        employment.put("tailoredDescription", "");
        return output;
    }

    private InvalidLlmResponseException assertRejectedAt(JsonNode output, String path)
            throws Exception {
        return assertRejectedAt(output, schema, path);
    }

    private com.jobseekercopilot.cvcoverletter.dto.GeneratedClaim claimFor(
            GeneratedApplicationDocuments documents,
            String contentPath
    ) {
        return documents.getClaims().stream()
                .filter(claim -> claim.getContentPaths().contains(contentPath))
                .findFirst()
                .orElseThrow();
    }

    private InvalidLlmResponseException assertRejectedAt(
            JsonNode output,
            JsonNode validationSchema,
            String path
    ) throws Exception {
        InvalidLlmResponseException error = assertThrows(
                InvalidLlmResponseException.class,
                () -> parser.parse(objectMapper.writeValueAsString(output), validationSchema));
        assertTrue(error.getMessage().contains(path), error.getMessage());
        return error;
    }

    private void removeProviderBounds(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.remove("pattern");
            objectNode.remove("minItems");
            objectNode.remove("maxItems");
            objectNode.fields().forEachRemaining(field -> removeProviderBounds(field.getValue()));
            return;
        }
        if (node.isArray()) {
            node.forEach(this::removeProviderBounds);
        }
    }
}
