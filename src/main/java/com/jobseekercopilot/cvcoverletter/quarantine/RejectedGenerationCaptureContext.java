package com.jobseekercopilot.cvcoverletter.quarantine;

import com.jobseekercopilot.cvcoverletter.dto.PromptGenerationMetadata;
import com.jobseekercopilot.generated.llmgateway.model.GenerationRequest;
import com.jobseekercopilot.generated.llmgateway.model.GenerationResponse;
import java.util.UUID;

public record RejectedGenerationCaptureContext(
        UUID operationId,
        String ownerId,
        PromptGenerationMetadata promptMetadata,
        String parserVersion,
        String claimPolicyVersion,
        GenerationRequest generationRequest,
        GenerationResponse generationResponse,
        RuntimeException rejection) {
}
