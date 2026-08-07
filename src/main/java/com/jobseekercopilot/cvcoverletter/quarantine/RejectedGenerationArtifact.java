package com.jobseekercopilot.cvcoverletter.quarantine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RejectedGenerationArtifact(
        int formatVersion,
        UUID operationId,
        String ownerId,
        Instant capturedAt,
        Instant expiresAt,
        String promptReleaseId,
        String promptBundleSha256,
        String schemaId,
        String schemaVersion,
        String schemaSha256,
        String evaluationPolicyVersion,
        String evaluationPolicySha256,
        String parserVersion,
        String claimPolicyVersion,
        String generationRequestSha256,
        String responseSha256,
        String responseJson,
        String modelId,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        List<RejectedGenerationReplayAuditEvent> auditEvents) {

    public RejectedGenerationArtifact withAuditEvents(
            List<RejectedGenerationReplayAuditEvent> replacement) {
        return new RejectedGenerationArtifact(
                formatVersion,
                operationId,
                ownerId,
                capturedAt,
                expiresAt,
                promptReleaseId,
                promptBundleSha256,
                schemaId,
                schemaVersion,
                schemaSha256,
                evaluationPolicyVersion,
                evaluationPolicySha256,
                parserVersion,
                claimPolicyVersion,
                generationRequestSha256,
                responseSha256,
                responseJson,
                modelId,
                inputTokens,
                outputTokens,
                totalTokens,
                List.copyOf(replacement));
    }
}
