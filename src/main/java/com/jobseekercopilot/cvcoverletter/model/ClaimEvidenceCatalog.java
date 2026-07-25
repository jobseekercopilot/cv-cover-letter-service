package com.jobseekercopilot.cvcoverletter.model;

import java.util.List;

public record ClaimEvidenceCatalog(
        String catalogVersion,
        List<ApprovedEvidenceRecord> records
) {}
