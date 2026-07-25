package com.jobseekercopilot.cvcoverletter.service;

import static com.jobseekercopilot.cvcoverletter.GenerationInputFixtures.validRequest;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.config.PromptBundleProperties;
import com.jobseekercopilot.cvcoverletter.model.CvCoverLetterPrompt;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class PromptBuilderServiceTest {

    @Test
    void separatesReviewedInstructionsUntrustedEvidenceAndOutputSchema() {
        LlmProperties properties = new LlmProperties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromptBuilderService service = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.3.0"),
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
        assertTrue(result.getTrustedInstructions().contains("bundle=cv-cover-letter@1.3.0"));
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
                "/properties/coverLetter/properties/bodyParagraphs/maxItems").asInt() == 7);
        assertTrue(result.getGenerationMetadata().bundleSha256().matches("[a-f0-9]{64}"));
        assertTrue(result.getGenerationMetadata().schemaVersion().equals("3.0.0"));
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
