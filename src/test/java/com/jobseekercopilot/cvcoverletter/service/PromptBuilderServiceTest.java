package com.jobseekercopilot.cvcoverletter.service;

import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validRequest;
import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validVersionedRequest;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.config.PromptBundleProperties;
import com.jobseekercopilot.cvcoverletter.model.CvCoverLetterPrompt;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class PromptBuilderServiceTest {

    @Test
    void onlyTheCanonicalSkillsReleasePublishesRevisionDeclaredSkills() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        GenerationInputNormalizer normalizer = new GenerationInputNormalizer(
                Clock.fixed(
                        Instant.parse("2026-07-24T13:00:00Z"),
                        ZoneOffset.UTC));
        var request = validVersionedRequest();
        request.getProfile().setSkills(java.util.List.of("Spring"));
        var input = normalizer.normalize("owner-secret-123", request);

        CvCoverLetterPrompt active = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.5.8"),
                new LlmProperties(),
                new ClaimEvidenceCatalogFactory())
                .buildPrompt(input);
        CvCoverLetterPrompt rollback = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.5.6"),
                new LlmProperties(),
                new ClaimEvidenceCatalogFactory())
                .buildPrompt(input);

        assertTrue(active.getEvidenceCatalog().records().stream()
                .anyMatch(record -> record.source()
                        == EvidenceSource.PROFILE_REVISION));
        assertFalse(rollback.getEvidenceCatalog().records().stream()
                .anyMatch(record -> record.source()
                        == EvidenceSource.PROFILE_REVISION));
    }

    @Test
    void separatesReviewedInstructionsUntrustedEvidenceAndOutputSchema() {
        LlmProperties properties = new LlmProperties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromptBuilderService service = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.5.8"),
                properties,
                new ClaimEvidenceCatalogFactory());
        GenerationInputNormalizer normalizer = new GenerationInputNormalizer(
                Clock.fixed(Instant.parse("2026-07-24T13:00:00Z"), ZoneOffset.UTC));

        CvCoverLetterPrompt result = service.buildPrompt(
                normalizer.normalize("owner-secret-123", validRequest()));

        assertTrue(result.getTrustedInstructions().contains("TRUTHFULNESS RULES"));
        assertTrue(result.getTrustedInstructions().contains("UK English"));
        assertTrue(result.getTrustedInstructions().contains("Aim for 5 to 7 concise paragraphs"));
        assertTrue(result.getTrustedInstructions().contains("specific to the job"));
        assertTrue(result.getTrustedInstructions().contains("bundle=cv-cover-letter@1.5.8"));
        assertTrue(result.getTrustedInstructions().contains(
                "Generic, professional and application prose is"));
        assertTrue(result.getTrustedInstructions().contains(
                "/coverLetter/bodyParagraphs/{i}"));
        assertTrue(result.getTrustedInstructions().contains(
                "ordinary claims[].contentPaths, personalSummaryClaim"));
        assertTrue(result.getTrustedInstructions().contains(
                "The claims ledger is final-content provenance only."));
        assertTrue(result.getTrustedInstructions().contains(
                "Do not emit CONFIRMATION_REQUIRED or REJECTED ledger entries"));
        assertTrue(result.getTrustedInstructions().contains("UNTRUSTED CONTENT RULES"));
        assertTrue(result.getTrustedInstructions().contains(
                "[CANONICAL JOB FACTS SUPPLIED THROUGH THE UNTRUSTED INPUT CHANNEL]"));
        assertTrue(result.getTrustedInstructions().contains(
                "[STRICT JSON SCHEMA SUPPLIED THROUGH THE OUTPUT CONTRACT]"));
        assertTrue(result.getUntrustedInput().contains("UNTRUSTED_DATA_ONLY"));
        assertTrue(result.getUntrustedInput().contains("Build useful and reliable services."));
        assertTrue(result.getUntrustedInput().contains("Built and maintained Java services."));
        assertTrue(result.getUntrustedInput().contains("\"evidenceId\""));
        assertTrue(result.getUntrustedInput().contains("PROFILE.SKILL.1"));
        assertTrue(result.getOutputSchema().has("properties"));
        assertTrue(result.getOutputSchema().toString().contains("\"coverLetter\""));
        assertTrue(result.getOutputSchema().path("additionalProperties").isBoolean());
        assertTrue(result.getOutputSchema().at("/properties/cv/properties/title/pattern").isTextual());
        assertTrue(result.getOutputSchema().at(
                "/properties/coverLetter/properties/bodyParagraphs/maxItems").asInt() == 5);
        assertTrue(result.getOutputSchema().at(
                "/properties/cv/properties/coreSkills/maxItems").asInt() == 12);
        assertTrue(result.getOutputSchema().at(
                "/properties/cv/properties/coreSkills/items/properties/evidence/enum/0")
                .asText().isEmpty());
        assertTrue(result.getOutputSchema().at(
                "/properties/cv/properties/projects").isObject());
        assertTrue(result.getGenerationMetadata().bundleSha256().matches("[a-f0-9]{64}"));
        assertTrue(result.getOutputSchema().at(
                "/properties/claims/items/properties/disposition/enum").toString()
                .equals("[\"SUPPORTED\",\"REWORDED\"]"));
        assertTrue(result.getOutputSchema().at(
                "/properties/claims/items/properties/evidenceIds/minItems").asInt() == 1);
        assertFalse(result.getOutputSchema().at(
                "/properties/claims/items/properties/evidenceIds/items")
                .has("enum"));
        assertTrue(result.getOutputSchema().at(
                "/properties/claims/items/properties/contentPaths/minItems").asInt() == 1);
        assertTrue(result.getOutputSchema().at(
                "/properties/claims/items/properties/reviewText/enum/0").asText().isEmpty());
        assertTrue(result.getOutputSchema().at(
                "/properties/coverLetter/properties/openingParagraph/enum/0")
                .asText()
                .equals("Please consider my application for this role."));
        assertTrue(result.getOutputSchema().at(
                "/properties/coverLetter/properties/closingParagraph/enum/0")
                .asText()
                .equals("Thank you for considering my application."));
        assertTrue(result.getOutputSchema().at(
                "/properties/canonicalApplicationClaims/properties/opening/properties/claimId/enum/0")
                .asText()
                .equals("CLAIM-9001"));
        assertTrue(result.getOutputSchema().at(
                "/properties/canonicalApplicationClaims/properties/closing/properties/claimId/enum/0")
                .asText()
                .equals("CLAIM-9002"));
        assertTrue(result.getOutputSchema().at(
                "/properties/canonicalApplicationClaims/properties/opening/properties/generationIntentEvidenceId/enum/0")
                .asText()
                .equals("REQUEST.GENERATION_INTENT"));
        assertTrue(result.getOutputSchema().at(
                "/properties/personalSummaryClaim/properties/claimId/enum/0")
                .asText()
                .equals("CLAIM-9003"));
        assertTrue(result.getOutputSchema().at(
                "/properties/personalSummaryClaim/properties/contentPath/enum/0")
                .asText()
                .equals("/cv/personalSummary"));
        int ordinaryClaimLimit = result.getOutputSchema().at(
                "/properties/claims/maxItems").asInt();
        int canonicalClaimCount = result.getOutputSchema().at(
                "/properties/canonicalApplicationClaims/properties").size();
        int personalSummaryClaimCount = 1;
        int reservedSkillClaimCapacity = 8;
        assertTrue(ordinaryClaimLimit == 29);
        assertTrue(ordinaryClaimLimit
                + personalSummaryClaimCount
                + canonicalClaimCount
                + reservedSkillClaimCapacity == 40);
        String ordinaryPathPattern = result.getOutputSchema().at(
                "/properties/claims/items/properties/contentPaths/items/pattern").asText();
        assertFalse("/coverLetter/openingParagraph".matches(ordinaryPathPattern));
        assertFalse("/coverLetter/closingParagraph".matches(ordinaryPathPattern));
        assertFalse("/cv/title".matches(ordinaryPathPattern));
        assertFalse("/cv/personalSummary".matches(ordinaryPathPattern));
        assertFalse("/coverLetter/title".matches(ordinaryPathPattern));
        assertFalse("/cv/coreSkills/0/name".matches(ordinaryPathPattern));
        assertFalse("/cv/coreSkills/0/evidence".matches(ordinaryPathPattern));
        assertFalse("/cv/qualifications/0/qualificationTitle"
                .matches(ordinaryPathPattern));
        assertTrue("/cv/qualifications/0/qualificationName"
                .matches(ordinaryPathPattern));
        assertFalse(result.getOutputSchema().toString().contains("CONFIRMATION_REQUIRED"));
        assertFalse(result.getOutputSchema().toString().contains("REJECTED"));
        assertFalse(result.getOutputSchema().toString().contains("PROFILE.SKILL.1"));
        assertFalse(result.getOutputSchema().toString().contains("Build useful and reliable services."));
        assertTrue(result.getGenerationMetadata().schemaVersion().equals("3.8.0"));
        assertTrue(result.getEvidenceCatalog().records().stream()
                .anyMatch(record -> record.evidenceId().equals("JOB.TITLE")));
        assertFalse(result.getTrustedInstructions().contains("Build useful and reliable services."));
        assertFalse(result.getTrustedInstructions().contains("Built and maintained Java services."));
        assertFalse(result.getTrustedInstructions().contains("\"coverLetter\""));
        assertFalse(result.getUntrustedInput().contains("TRUTHFULNESS RULES"));
        assertFalse(result.getUntrustedInput().contains("\"coverLetter\""));
        assertFalse(result.getUntrustedInput().contains("owner-secret-123"));
        assertFalse(result.getUntrustedInput().contains("profile-123"));
        assertFalse(result.getUntrustedInput().contains("profile-v7"));
        assertFalse(result.getUntrustedInput().contains("job-456"));
        assertFalse(result.getUntrustedInput().contains("job-v12"));
        assertFalse(result.getUntrustedInput().contains("Alex Candidate"));
        assertFalse(result.getUntrustedInput().contains("alex@example.com"));
        assertFalse(result.toString().contains("Build useful and reliable services."));
        assertFalse(result.toString().contains("TRUTHFULNESS RULES"));
    }

    private PromptBundleRegistry registry(ObjectMapper objectMapper, String releaseId) {
        PromptBundleProperties properties = new PromptBundleProperties();
        properties.setSelectedReleaseId(releaseId);
        PromptBundleRegistry registry =
                new PromptBundleRegistry(objectMapper, new DefaultResourceLoader(), properties);
        registry.initialize();
        return registry;
    }
}
