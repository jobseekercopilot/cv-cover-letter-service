package com.jobseekercopilot.cvcoverletter.service;

public record PromptBundleComparison(
        String fromReleaseId,
        String toReleaseId,
        boolean templateChanged,
        boolean rulesChanged,
        boolean schemaChanged,
        boolean evaluationPolicyChanged
) {
}
