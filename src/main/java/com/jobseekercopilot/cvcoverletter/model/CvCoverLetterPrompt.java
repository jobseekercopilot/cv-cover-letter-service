package com.jobseekercopilot.cvcoverletter.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CvCoverLetterPrompt {
    String taskType;
    String language;
    String rules;
    String outputSchemaJson;
    String profileInputJson;
    String jobJson;
    String inputWarningsJson;
    String finalPrompt;
}
