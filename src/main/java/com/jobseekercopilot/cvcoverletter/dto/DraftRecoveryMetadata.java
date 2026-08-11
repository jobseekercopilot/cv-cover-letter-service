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
        String fallbackVersion) {
}
