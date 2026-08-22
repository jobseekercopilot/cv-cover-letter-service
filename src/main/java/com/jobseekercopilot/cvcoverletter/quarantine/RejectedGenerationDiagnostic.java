package com.jobseekercopilot.cvcoverletter.quarantine;

public record RejectedGenerationDiagnostic(
        String phase,
        String path,
        String reason) {
}
