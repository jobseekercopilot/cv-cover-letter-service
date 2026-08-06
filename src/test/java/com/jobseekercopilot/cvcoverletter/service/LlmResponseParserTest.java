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
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmResponseParserTest {

    private ObjectMapper objectMapper;
    private LlmResponseParser parser;
    private JsonNode schema;
    private JsonNode legacySchema;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        parser = new LlmResponseParser(
                objectMapper,
                new ClaimEvidenceValidator(),
                new GeneratedDocumentQualityValidator());
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.5.4/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Active output schema fixture is missing.");
            }
            schema = objectMapper.readTree(input);
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
        assertEquals(10, result.getClaims().size());
        assertEquals("CLAIM-9001", result.getClaims().get(8).getClaimId());
        assertEquals("CLAIM-9002", result.getClaims().get(9).getClaimId());
        assertEquals("3.3.0", parser.parserVersion(schema));
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
    void ordinaryClaimsCannotUseReservedIdsOrCanonicalBookendPaths()
            throws Exception {
        JsonNode reservedId = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ((ObjectNode) reservedId.at("/claims/0"))
                .put("claimId", "CLAIM-9001");
        assertRejectedAt(reservedId, "$.claims[0].claimId");

        for (String path : new String[] {
                "/coverLetter/openingParagraph",
                "/coverLetter/closingParagraph"
        }) {
            JsonNode groupedBookend = objectMapper.readTree(
                    CvCoverLetterServiceTest.activeValidJson());
            ((ArrayNode) groupedBookend.at("/claims/0/contentPaths"))
                    .set(0, objectMapper.getNodeFactory().textNode(path));
            assertRejectedAt(groupedBookend, "$.claims[0].contentPaths[0]");
        }
    }

    @Test
    void reservesCapacityForExactlyTwoProjectedCanonicalClaims()
            throws Exception {
        JsonNode bounded = objectMapper.readTree(
                CvCoverLetterServiceTest.activeValidJson());
        ArrayNode claims = (ArrayNode) bounded.path("claims");
        while (claims.size() < 38) {
            ObjectNode copy = claims.get(0).deepCopy();
            copy.put("claimId", "CLAIM-" + (100 + claims.size()));
            claims.add(copy);
        }

        GeneratedApplicationDocuments accepted = parser.parse(
                objectMapper.writeValueAsString(bounded), schema);
        assertEquals(40, accepted.getClaims().size());

        ObjectNode extra = claims.get(0).deepCopy();
        extra.put("claimId", "CLAIM-888");
        claims.add(extra);
        assertRejectedAt(bounded, "$.claims");
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
                .put("maxItems", "38");
        assertThrows(
                IllegalStateException.class,
                () -> parser.parserVersion(nonNumericBound));

        JsonNode excessiveBound = schema.deepCopy();
        ((ObjectNode) excessiveBound.at("/properties/claims"))
                .put("maxItems", 39);
        assertThrows(
                IllegalStateException.class,
                () -> parser.parserVersion(excessiveBound));

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

    private InvalidLlmResponseException assertRejectedAt(JsonNode output, String path)
            throws Exception {
        return assertRejectedAt(output, schema, path);
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
