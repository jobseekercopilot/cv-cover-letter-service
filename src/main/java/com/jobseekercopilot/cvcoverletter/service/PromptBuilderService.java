package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.model.CvCoverLetterPrompt;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PromptBuilderService {
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\{\\{[A-Z0-9_]+}}");

    private final ObjectMapper objectMapper;
    private final PromptBundleRegistry promptBundleRegistry;
    private final LlmProperties llmProperties;

    public CvCoverLetterPrompt buildPrompt(NormalizedGenerationInput input) {
        try {
            PromptBundle bundle = promptBundleRegistry.selected();
            String template = bundle.template();
            String rules = bundle.rules();
            String outputSchemaJson = bundle.outputSchemaJson();

            String profileInputJson = toPrettyJson(input.profile());
            String jobJson = toPrettyJson(input.job());
            String inputWarningsJson = toPrettyJson(input.warnings());

            String finalPrompt = template
                    .replace("{{LANGUAGE}}", llmProperties.getLanguage())
                    .replace("{{PROMPT_BUNDLE_ID}}", bundle.metadata().bundleId())
                    .replace("{{PROMPT_BUNDLE_VERSION}}", bundle.metadata().bundleVersion())
                    .replace("{{TEMPLATE_VERSION}}", bundle.metadata().templateVersion())
                    .replace("{{RULES_VERSION}}", bundle.metadata().rulesVersion())
                    .replace("{{SCHEMA_ID}}", bundle.metadata().schemaId())
                    .replace("{{SCHEMA_VERSION}}", bundle.metadata().schemaVersion())
                    .replace("{{RULES}}", rules)
                    .replace("{{OUTPUT_SCHEMA_JSON}}", outputSchemaJson)
                    .replace("{{PROFILE_INPUT_JSON}}", profileInputJson)
                    .replace("{{JOB_INPUT_JSON}}", jobJson)
                    .replace("{{INPUT_WARNINGS_JSON}}", inputWarningsJson);
            if (UNRESOLVED_PLACEHOLDER.matcher(finalPrompt).find()) {
                throw new IllegalStateException(
                        "Selected prompt bundle contains an unresolved contract placeholder.");
            }

            return CvCoverLetterPrompt.builder()
                    .taskType(llmProperties.getTaskType())
                    .language(llmProperties.getLanguage())
                    .rules(rules)
                    .outputSchemaJson(outputSchemaJson)
                    .profileInputJson(profileInputJson)
                    .jobJson(jobJson)
                    .inputWarningsJson(inputWarningsJson)
                    .finalPrompt(finalPrompt)
                    .generationMetadata(bundle.metadata())
                    .build();

        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to build CV and cover letter prompt", e);
        }
    }

    private String toPrettyJson(Object value) throws JsonProcessingException {
        return objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(value);
    }
}
