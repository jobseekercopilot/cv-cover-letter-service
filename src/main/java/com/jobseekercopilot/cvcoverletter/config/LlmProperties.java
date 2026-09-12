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
    private Double temperature = 0.0;
    private Integer maxTokens = 16384;

    /**
     * Maximum number of additional model attempts made when a selected draft
     * fails deterministic output validation. Zero preserves single-attempt
     * behaviour. Each retry is a fresh provider call, so this is capped low to
     * bound latency and provider cost.
     */
    private Integer maxValidationRetries = 2;

    /**
     * Temperature applied to validation-retry attempts. The first attempt uses
     * {@link #temperature}; retries raise it so a deterministic near-miss can
     * vary enough to satisfy the unchanged validators. Bounded to [0, 1].
     */
    private Double retryTemperature = 0.4;
}
