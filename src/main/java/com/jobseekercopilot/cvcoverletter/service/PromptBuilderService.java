package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.dto.Job;
import com.jobseekercopilot.cvcoverletter.dto.UserProfile;
import com.jobseekercopilot.cvcoverletter.model.CvCoverLetterPrompt;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class PromptBuilderService {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final LlmProperties llmProperties;

    public CvCoverLetterPrompt buildPrompt(UserProfile userProfile, Job job) {
        try {
            String template = readResource("classpath:prompts/cv-cover-letter-prompt-template.txt");
            String rules = readResource("classpath:prompts/generation-rules.txt");
            String outputSchemaJson = readResource("classpath:prompts/output-schema.json");

            String userProfileJson = toPrettyJson(userProfile);
            String jobJson = toPrettyJson(job);

            String finalPrompt = template
                    .replace("{{LANGUAGE}}", llmProperties.getLanguage())
                    .replace("{{RULES}}", rules)
                    .replace("{{OUTPUT_SCHEMA_JSON}}", outputSchemaJson)
                    .replace("{{USER_PROFILE_JSON}}", userProfileJson)
                    .replace("{{JOB_JSON}}", jobJson);

            return CvCoverLetterPrompt.builder()
                    .taskType(llmProperties.getTaskType())
                    .language(llmProperties.getLanguage())
                    .rules(rules)
                    .outputSchemaJson(outputSchemaJson)
                    .userProfileJson(userProfileJson)
                    .jobJson(jobJson)
                    .finalPrompt(finalPrompt)
                    .build();

        } catch (IOException e) {
            throw new IllegalStateException("Failed to build CV and cover letter prompt", e);
        }
    }

    private String readResource(String location) throws IOException {
        Resource resource = resourceLoader.getResource(location);
        return StreamUtils.copyToString(
                resource.getInputStream(),
                StandardCharsets.UTF_8
        );
    }

    private String toPrettyJson(Object value) throws JsonProcessingException {
        return objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(value);
    }
}
