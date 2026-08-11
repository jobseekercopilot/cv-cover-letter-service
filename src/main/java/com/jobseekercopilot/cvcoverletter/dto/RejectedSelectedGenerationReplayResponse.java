package com.jobseekercopilot.cvcoverletter.dto;

import com.jobseekercopilot.cvcoverletter.quarantine.RejectedGenerationDiagnostic;
import java.time.Instant;
import java.util.UUID;

public record RejectedSelectedGenerationReplayResponse(
        UUID operationId,
        Instant replayedAt,
        String outcome,
        int providerInvocationCount,
        String parserVersion,
        String claimPolicyVersion,
        RejectedGenerationDiagnostic diagnostic,
        SelectedDraftGenerationResponse draft) {
}
