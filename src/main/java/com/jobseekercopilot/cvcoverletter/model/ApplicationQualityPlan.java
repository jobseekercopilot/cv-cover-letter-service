package com.jobseekercopilot.cvcoverletter.model;

import java.util.List;

/**
 * Deterministic, non-factual guidance derived from the vacancy and approved
 * evidence. The model may use it to prioritise evidence, but the approved
 * evidence catalogue remains the only factual source.
 */
public record ApplicationQualityPlan(
        String version,
        List<VacancyEmphasis> vacancyEmphasis,
        List<RankedEvidence> rankedEvidence,
        List<String> qualityObjectives
) {
    public record VacancyEmphasis(
            String concept,
            String category,
            int priority,
            List<String> matchedAdvertTerms
    ) {
    }

    public record RankedEvidence(
            String evidenceId,
            int score,
            List<String> reasons
    ) {
    }
}
