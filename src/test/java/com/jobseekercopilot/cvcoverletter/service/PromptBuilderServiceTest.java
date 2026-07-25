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
    void includesOnlyApprovedNormalizedEvidenceRulesAndOutputSchema() {
        LlmProperties properties = new LlmProperties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromptBuilderService service = new PromptBuilderService(
                objectMapper,
                registry(objectMapper, "cv-cover-letter-1.1.0"),
                properties);
        GenerationInputNormalizer normalizer = new GenerationInputNormalizer(
                Clock.fixed(Instant.parse("2026-07-24T13:00:00Z"), ZoneOffset.UTC));

        CvCoverLetterPrompt result = service.buildPrompt(
                normalizer.normalize("owner-secret-123", validRequest()));

        assertTrue(result.getFinalPrompt().contains("Build useful and reliable services."));
        assertTrue(result.getFinalPrompt().contains("Built and maintained Java services."));
        assertTrue(result.getFinalPrompt().contains("TRUTHFULNESS RULES"));
        assertTrue(result.getFinalPrompt().contains("\"coverLetter\""));
        assertTrue(result.getFinalPrompt().contains("UK English"));
        assertTrue(result.getFinalPrompt().contains("aim for 5 to 7 concise paragraphs"));
        assertTrue(result.getFinalPrompt().contains("role and company fit"));
        assertTrue(result.getFinalPrompt().contains("bundle=cv-cover-letter@1.1.0"));
        assertTrue(result.getFinalPrompt().contains("UNTRUSTED CONTENT RULES"));
        assertTrue(result.getFinalPrompt().contains("UNTRUSTED CANONICAL JOB FACTS"));
        assertTrue(result.getGenerationMetadata().bundleSha256().matches("[a-f0-9]{64}"));
        assertTrue(result.getGenerationMetadata().schemaVersion().equals("1.0.0"));
        assertFalse(result.getFinalPrompt().contains("owner-secret-123"));
        assertFalse(result.getFinalPrompt().contains("profile-123"));
        assertFalse(result.getFinalPrompt().contains("profile-v7"));
        assertFalse(result.getFinalPrompt().contains("job-456"));
        assertFalse(result.getFinalPrompt().contains("job-v12"));
        assertFalse(result.getFinalPrompt().contains("Alex Candidate"));
        assertFalse(result.getFinalPrompt().contains("alex@example.com"));
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
