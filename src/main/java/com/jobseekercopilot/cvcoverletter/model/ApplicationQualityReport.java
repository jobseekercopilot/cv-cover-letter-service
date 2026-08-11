package com.jobseekercopilot.cvcoverletter.model;

import java.util.List;

/** Product-evaluation metrics. These are quality proxies, not interview odds. */
public record ApplicationQualityReport(
        String version,
        boolean groundingPass,
        Metrics metrics,
        List<String> strongestEvidenceUsed,
        List<String> strongEvidenceOmitted,
        List<String> findings
) {
    public record Metrics(
            int jdRelevance,
            int evidenceCoverage,
            int strongEvidenceUtilisation,
            int weakEvidenceOveruse,
            int cvCoverLetterComplementarity,
            int summarySpecificity,
            int bulletSpecificity,
            int readability,
            int concision,
            int roleSeniorityFit,
            int overallInterviewPersuasiveness
    ) {
    }
}
