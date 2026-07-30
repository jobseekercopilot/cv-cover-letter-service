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

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        parser = new LlmResponseParser(
                objectMapper,
                new ClaimEvidenceValidator(),
                new GeneratedDocumentQualityValidator());
        try (InputStream input = getClass().getResourceAsStream(
                "/prompts/bundles/cv-cover-letter-1.5.3/output-schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Active output schema fixture is missing.");
            }
            schema = objectMapper.readTree(input);
        }
    }

    @Test
    void parsesJsonThatMatchesTheExactProviderSchema() {
        GeneratedApplicationDocuments result =
                parser.parse(CvCoverLetterServiceTest.validJson(), schema);

        assertEquals("Tailored Developer CV", result.getCv().getTitle());
        assertEquals("Developer Cover Letter", result.getCoverLetter().getTitle());
        assertEquals("Focused on Java.", result.getGenerationNotes().getTailoringSummary());
    }

    @Test
    void rejectsReviewOnlyOrStructurallyEmptyClaimsAtTheActiveSchemaBoundary()
            throws Exception {
        for (String disposition :
                new String[] {"CONFIRMATION_REQUIRED", "REJECTED"}) {
            JsonNode reviewOnly =
                    objectMapper.readTree(CvCoverLetterServiceTest.validJson());
            ((ObjectNode) reviewOnly.at("/claims/0"))
                    .put("disposition", disposition);
            assertRejectedAt(reviewOnly, "$.claims[0].disposition");
        }

        JsonNode emptyEvidence =
                objectMapper.readTree(CvCoverLetterServiceTest.validJson());
        ((ArrayNode) emptyEvidence.at("/claims/0/evidenceIds")).removeAll();
        assertRejectedAt(emptyEvidence, "$.claims[0].evidenceIds");

        JsonNode emptyPaths =
                objectMapper.readTree(CvCoverLetterServiceTest.validJson());
        ((ArrayNode) emptyPaths.at("/claims/0/contentPaths")).removeAll();
        assertRejectedAt(emptyPaths, "$.claims[0].contentPaths");

        JsonNode reviewText =
                objectMapper.readTree(CvCoverLetterServiceTest.validJson());
        ((ObjectNode) reviewText.at("/claims/0"))
                .put("reviewText", "Please confirm this claim.");
        assertRejectedAt(reviewText, "$.claims[0].reviewText");

        JsonNode terminalLineSeparator =
                objectMapper.readTree(CvCoverLetterServiceTest.validJson());
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
                    objectMapper.readTree(CvCoverLetterServiceTest.validJson());
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
                    objectMapper.readTree(CvCoverLetterServiceTest.validJson());
            ((ObjectNode) output.path("coverLetter"))
                    .put("closingParagraph", closing);
            assertRejectedAt(output, "$.coverLetter.closingParagraph");
        }
    }

    @Test
    void rejectsMalformedTruncatedFencedTrailingAndDuplicateJsonWithoutEchoingPayload() {
        for (String response : new String[] {
                "response-secret-sentinel",
                CvCoverLetterServiceTest.validJson().substring(0, 80),
                "```json\n" + CvCoverLetterServiceTest.validJson() + "\n```",
                CvCoverLetterServiceTest.validJson() + "{}",
                CvCoverLetterServiceTest.validJson().replace(
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
        JsonNode missing = objectMapper.readTree(CvCoverLetterServiceTest.validJson());
        ((ObjectNode) missing.path("cv")).remove("title");
        assertRejectedAt(missing, "$.cv.title");

        JsonNode nullList = objectMapper.readTree(CvCoverLetterServiceTest.validJson());
        ((ObjectNode) nullList.path("cv"))
                .putNull("coreSkills");
        assertRejectedAt(nullList, "$.cv.coreSkills");

        JsonNode unknown = objectMapper.readTree(CvCoverLetterServiceTest.validJson());
        ((ObjectNode) unknown.path("coverLetter"))
                .put("secretField", "response-secret-sentinel");
        InvalidLlmResponseException unknownError = assertRejectedAt(unknown, "$.coverLetter.secretField");
        assertFalse(unknownError.getMessage().contains("response-secret-sentinel"));

        JsonNode wrongType = objectMapper.readTree(CvCoverLetterServiceTest.validJson());
        ((ObjectNode) wrongType.path("cv"))
                .put("personalSummary", 42);
        assertRejectedAt(wrongType, "$.cv.personalSummary");
    }

    @Test
    void rejectsOversizedTextArraysAndWholeResponses() throws Exception {
        JsonNode oversizedTitle = objectMapper.readTree(CvCoverLetterServiceTest.validJson());
        ((ObjectNode) oversizedTitle.path("cv"))
                .put("title", "x".repeat(201));
        assertRejectedAt(oversizedTitle, "$.cv.title");

        JsonNode oversizedArray = objectMapper.readTree(CvCoverLetterServiceTest.validJson());
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
        JsonNode legacySchema = schema.deepCopy();
        removeProviderBounds(legacySchema);

        JsonNode oversizedText = objectMapper.readTree(CvCoverLetterServiceTest.validJson());
        ((ObjectNode) oversizedText.path("cv"))
                .put("title", "x".repeat(LlmResponseParser.MAX_FALLBACK_TEXT_CHARACTERS + 1));
        assertRejectedAt(oversizedText, legacySchema, "$.cv.title");

        JsonNode oversizedArray = objectMapper.readTree(CvCoverLetterServiceTest.validJson());
        ArrayNode assumptions = (ArrayNode) oversizedArray.path("generationNotes").path("assumptionsMade");
        while (assumptions.size() <= LlmResponseParser.MAX_FALLBACK_ARRAY_ITEMS) {
            assumptions.add("Bounded assumption");
        }
        assertRejectedAt(oversizedArray, legacySchema, "$.generationNotes.assumptionsMade");
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
            String response = CvCoverLetterServiceTest.validJson()
                    .replace("A Java developer focused on useful services.", payload);
            InvalidLlmResponseException error = assertThrows(
                    InvalidLlmResponseException.class,
                    () -> parser.parse(response, schema));
            assertTrue(
                    error.getMessage().contains("$.cv.personalSummary"),
                    payload + " => " + error.getMessage());
            assertFalse(error.getMessage().contains(payload));
        }

        JsonNode control = objectMapper.readTree(CvCoverLetterServiceTest.validJson());
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
