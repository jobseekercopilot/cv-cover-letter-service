package com.jobseekercopilot.cvcoverletter.dto;

import com.jobseekercopilot.cvcoverletter.quarantine.RejectedGenerationDiagnostic;
import java.time.Instant;
import java.util.UUID;

public record RejectedGenerationMetadataResponse(
        UUID operationId,
        Instant capturedAt,
        Instant expiresAt,
        String promptReleaseId,
        String promptBundleSha256,
        String schemaId,
        String schemaVersion,
        String schemaSha256,
        String evaluationPolicyVersion,
        String parserVersionAtCapture,
        String claimPolicyVersionAtCapture,
        String responseSha256,
        String modelId,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        int replayCount,
        RejectedGenerationDiagnostic latestDiagnostic) {
}
