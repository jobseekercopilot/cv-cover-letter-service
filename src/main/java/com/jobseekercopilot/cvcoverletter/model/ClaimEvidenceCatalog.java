package com.jobseekercopilot.cvcoverletter.model;

import java.util.List;
import java.util.Map;

public record ClaimEvidenceCatalog(
        String catalogVersion,
        List<ApprovedEvidenceRecord> records,
        Map<EvidencePurpose, List<String>> sectionOrder
) {
    public ClaimEvidenceCatalog(
            String catalogVersion,
            List<ApprovedEvidenceRecord> records) {
        this(catalogVersion, records, Map.of());
    }
}
