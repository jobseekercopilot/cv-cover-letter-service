package com.jobseekercopilot.cvcoverletter.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "prompt")
public class PromptBundleProperties {
    private String selectedReleaseId = "cv-cover-letter-1.1.0";
}
