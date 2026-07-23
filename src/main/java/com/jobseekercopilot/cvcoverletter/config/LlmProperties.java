package com.jobseekercopilot.cvcoverletter.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    private String taskType = "CV_COVER_LETTER_GENERATION";
    private String language = "UK English";
    private Double temperature = 0.3;
    private Integer maxTokens = 3000;
}
