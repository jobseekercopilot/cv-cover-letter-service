package com.jobseekercopilot.cvcoverletter.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Content-free recovery evidence for one generated output.")
public record DraftRecoveryMetadata(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String finalSource,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean structuralRepairAttempted,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean structuralRepairSucceeded,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int duplicateItemsRemoved,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean retainedResponseReplay,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean fallbackUsed,
        String fallbackReason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String fallbackVersion,
        @Schema(
                description = "Bounded provider attempts reported by the model gateway.")
        int providerAttemptCount,
        @Schema(
                description = "Safe automatic retries reported by the model gateway.")
        int automaticRetryCount,
        boolean retried,
        @Schema(
                description = "Allowlisted retry category; never a provider error message.",
                allowableValues = {"RATE_LIMITED"})
        String retryReason) {

    public DraftRecoveryMetadata(
            String finalSource,
            boolean structuralRepairAttempted,
            boolean structuralRepairSucceeded,
            int duplicateItemsRemoved,
            boolean retainedResponseReplay,
            boolean fallbackUsed,
            String fallbackReason,
            String fallbackVersion) {
        this(
                finalSource,
                structuralRepairAttempted,
                structuralRepairSucceeded,
                duplicateItemsRemoved,
                retainedResponseReplay,
                fallbackUsed,
                fallbackReason,
                fallbackVersion,
                "LLM".equals(finalSource) ? 1 : 0,
                0,
                false,
                null);
    }
}
