package com.jobseekercopilot.cvcoverletter.service;

import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertRejected(missingCoverage, "unaccounted claim path");

        ObjectNode duplicateCoverage = validOutput();
        ((ArrayNode) duplicateCoverage.at("/claims/1/contentPaths")).add("/cv/targetRole");
        assertRejected(duplicateCoverage, "covered more than once");

        ObjectNode reviewOnlyFinalPath = validOutput();
        ObjectNode claim = (ObjectNode) reviewOnlyFinalPath.at("/claims/0");
        claim.put("disposition", "CONFIRMATION_REQUIRED");
        claim.put("reviewText", "Please confirm the target role.");
        assertRejected(reviewOnlyFinalPath, "review-only claim points at final content");
    }

    @Test
    void rejectsFabricatedTitleDateMetricToolAndQualification() throws Exception {
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
        assertRejected(fabricatedTitle, "atomic final claim is not an exact approved fact");

        ObjectNode fabricatedQualification = validOutput();
        addQualification(fabricatedQualification, "PhD Computing", "2024");
        assertRejected(fabricatedQualification, "atomic final claim is not an exact approved fact");

        ObjectNode fabricatedDate = validOutput();
        addQualification(fabricatedDate, "BSc Computing", "2019");
        assertRejected(fabricatedDate, "atomic final claim is not an exact approved fact");
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
