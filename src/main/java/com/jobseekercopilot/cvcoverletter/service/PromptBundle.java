package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.dto.PromptGenerationMetadata;

record PromptBundle(
        PromptGenerationMetadata metadata,
        String releaseStatus,
        String template,
        String rules,
        String outputSchemaJson
) {
}
