package com.jobseekercopilot.cvcoverletter.quarantine;

import java.time.Instant;

public record RejectedGenerationReplayAuditEvent(
        long sequence,
        Instant recordedAt,
        String action,
        String outcome,
        String validatorFingerprint,
        RejectedGenerationDiagnostic diagnostic,
        String previousEventSha256,
        String eventSha256) {
}
