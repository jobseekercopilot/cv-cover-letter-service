package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.dto.Job;
import com.jobseekercopilot.cvcoverletter.dto.UserProfile;
import com.jobseekercopilot.cvcoverletter.model.CvCoverLetterPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderServiceTest {

    @Test
    void includesProfileJobRulesAndOutputSchema() {
        LlmProperties properties = new LlmProperties();
        PromptBuilderService service = new PromptBuilderService(
                new ObjectMapper(), new DefaultResourceLoader(), properties);

        UserProfile profile = new UserProfile();
        profile.setUserId("profile-marker-123");
        Job job = new Job();
        job.setId("job-marker-456");
        job.setTitle("Backend Developer");
        job.setDescription("A distinctive job-description marker");

        CvCoverLetterPrompt result = service.buildPrompt(profile, job);

        assertTrue(result.getFinalPrompt().contains("profile-marker-123"));
        assertTrue(result.getFinalPrompt().contains("job-marker-456"));
        assertTrue(result.getFinalPrompt().contains("A distinctive job-description marker"));
        assertTrue(result.getFinalPrompt().contains("TRUTHFULNESS RULES"));
        assertTrue(result.getFinalPrompt().contains("\"coverLetter\""));
        assertTrue(result.getFinalPrompt().contains("UK English"));
        assertTrue(result.getFinalPrompt().contains("aim for 5 to 7 concise paragraphs"));
        assertTrue(result.getFinalPrompt().contains("role and company fit"));
    }
}
