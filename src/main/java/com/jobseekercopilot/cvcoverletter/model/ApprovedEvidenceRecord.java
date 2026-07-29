package com.jobseekercopilot.cvcoverletter.model;

public record ApprovedEvidenceRecord(
        String evidenceId,
        EvidenceSource source,
        String sourcePath,
        String value,
        String factType,
        String category,
        EvidencePurpose purpose
) {
    public ApprovedEvidenceRecord(
            String evidenceId,
            EvidenceSource source,
            String sourcePath,
            String value) {
        this(
                evidenceId,
                source,
                sourcePath,
                value,
                null,
                null,
                EvidencePurpose.BOTH);
    }
}
