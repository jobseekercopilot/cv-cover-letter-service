package com.jobseekercopilot.cvcoverletter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(
        description = """
                Bounded generated draft content and non-payload model evidence.
                This response does not create documents, applications or billing state.
                """)
public record DraftGenerationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID operationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String cvTitle,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String coverLetterTitle,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String cvContent,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String coverLetterContent,
        GenerationNotes generationNotes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        PromptGenerationMetadata generationMetadata,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String inputSchemaVersion,
        List<InputWarning> inputWarnings,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        ValidatedClaimLedger claimLedger,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        DraftGenerationUsage usage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        DraftGenerationAudit audit) {

    public record DraftGenerationUsage(
            Long inputTokens,
            Long outputTokens,
            Long totalTokens) {
    }

    public record DraftGenerationAudit(
            String modelId,
            String modelDeploymentVersion,
            String admissionPolicyVersion,
            String pricingVersion,
            Long estimatedInputTokensAtAdmission,
            Long estimatedCostMicroUsd,
            String currency) {
    }
}
