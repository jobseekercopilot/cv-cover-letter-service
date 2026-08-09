package com.jobseekercopilot.cvcoverletter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(
        description = "One explicitly selected generated draft and its non-payload model evidence.")
public record SelectedDraftGenerationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID operationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        DraftOutputType outputType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String content,
        GenerationNotes generationNotes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        PromptGenerationMetadata generationMetadata,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String inputSchemaVersion,
        List<InputWarning> inputWarnings,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        ValidatedClaimLedger claimLedger,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        DraftGenerationResponse.DraftGenerationUsage usage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        DraftGenerationResponse.DraftGenerationAudit audit) {
}
