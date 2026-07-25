package com.jobseekercopilot.cvcoverletter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Non-PII immutable prompt provenance. Prompt and source payloads are never included.")
public record PromptGenerationMetadata(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String releaseId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String bundleId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9]+\\.[0-9]+\\.[0-9]+$")
        String bundleVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-f0-9]{64}$")
        String bundleSha256,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9]+\\.[0-9]+\\.[0-9]+$")
        String templateVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-f0-9]{64}$")
        String templateSha256,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9]+\\.[0-9]+\\.[0-9]+$")
        String rulesVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-f0-9]{64}$")
        String rulesSha256,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String schemaId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9]+\\.[0-9]+\\.[0-9]+$")
        String schemaVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-f0-9]{64}$")
        String schemaSha256,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9]+\\.[0-9]+\\.[0-9]+$")
        String evaluationPolicyVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-f0-9]{64}$")
        String evaluationPolicySha256
) {
}
