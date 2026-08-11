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
        DraftGenerationResponse.DraftGenerationAudit audit,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long billableTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        DraftRecoveryMetadata recovery) {

    public SelectedDraftGenerationResponse(
            UUID operationId,
            DraftOutputType outputType,
            String title,
            String content,
            GenerationNotes generationNotes,
            PromptGenerationMetadata generationMetadata,
            String inputSchemaVersion,
            List<InputWarning> inputWarnings,
            ValidatedClaimLedger claimLedger,
            DraftGenerationResponse.DraftGenerationUsage usage,
            DraftGenerationResponse.DraftGenerationAudit audit) {
        this(
                operationId,
                outputType,
                title,
                content,
                generationNotes,
                generationMetadata,
                inputSchemaVersion,
                inputWarnings,
                claimLedger,
                usage,
                audit,
                usage == null || usage.totalTokens() == null
                        ? 0
                        : usage.totalTokens(),
                new DraftRecoveryMetadata(
                        "LLM",
                        false,
                        false,
                        0,
                        false,
                        false,
                        null,
                        DeterministicFallbackVersions.NONE));
    }
}
