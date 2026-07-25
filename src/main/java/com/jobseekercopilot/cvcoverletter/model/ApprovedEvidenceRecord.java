package com.jobseekercopilot.cvcoverletter.model;

public record ApprovedEvidenceRecord(
        String evidenceId,
        EvidenceSource source,
        String sourcePath,
        String value
) {}
