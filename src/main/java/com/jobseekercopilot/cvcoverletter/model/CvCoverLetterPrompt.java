package com.jobseekercopilot.cvcoverletter.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobseekercopilot.cvcoverletter.dto.PromptGenerationMetadata;
import lombok.Builder;
import lombok.ToString;
import lombok.Value;

@Value
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class CvCoverLetterPrompt {
    @ToString.Include
    String taskType;
    String trustedInstructions;
    String untrustedInput;
    JsonNode outputSchema;
    @ToString.Include
    PromptGenerationMetadata generationMetadata;
}
