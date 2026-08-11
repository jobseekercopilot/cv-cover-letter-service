package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.dto.ClaimDisposition;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedClaim;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedCoverLetter;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedCv;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedProject;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedWorkHistory;
import com.jobseekercopilot.cvcoverletter.model.ApplicationQualityPlan;
import com.jobseekercopilot.cvcoverletter.model.ApplicationQualityPlan.RankedEvidence;
import com.jobseekercopilot.cvcoverletter.model.ApplicationQualityReport;
import com.jobseekercopilot.cvcoverletter.model.ApplicationQualityReport.Metrics;
import com.jobseekercopilot.cvcoverletter.model.ApprovedEvidenceRecord;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Independent deterministic application-quality evaluation. It does not alter
 * generated documents and deliberately reports quality proxies rather than a
 * probability of interview or employment.
 */
@Component
public class ApplicationQualityEvaluator {
    public static final String VERSION = "application-quality-evaluator-1.0.0";
    private final ApplicationQualityPlanner planner =
            new ApplicationQualityPlanner();

    public ApplicationQualityReport evaluate(
            GeneratedApplicationDocuments documents,
            ClaimEvidenceCatalog catalog
    ) {
        ApplicationQualityPlan plan = planner.plan(catalog);
        Map<String, Integer> scoreByEvidence = plan.rankedEvidence().stream()
                .collect(Collectors.toMap(
                        RankedEvidence::evidenceId,
                        RankedEvidence::score,
                        Math::max));
        Set<String> approvedIds = catalog.records().stream()
                .map(ApprovedEvidenceRecord::evidenceId)
                .collect(Collectors.toSet());
        List<GeneratedClaim> claims = safe(documents.getClaims());
        Set<String> cited = claims.stream()
                .flatMap(claim -> safe(claim.getEvidenceIds()).stream())
                .filter(approvedIds::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean grounding = !claims.isEmpty()
                && claims.stream().allMatch(claim ->
                        claim != null
                                && (claim.getDisposition()
                                        == ClaimDisposition.SUPPORTED
                                    || claim.getDisposition()
                                        == ClaimDisposition.REWORDED)
                                && !safe(claim.getEvidenceIds()).isEmpty()
                                && approvedIds.containsAll(
                                        safe(claim.getEvidenceIds())));

        List<RankedEvidence> candidateRankings = plan.rankedEvidence().stream()
                .filter(ranked -> scoreByEvidence.containsKey(ranked.evidenceId()))
                .toList();
        List<RankedEvidence> strongest = candidateRankings.stream()
                .filter(ranked -> ranked.score() > 0)
                .limit(10)
                .toList();
        int strongUse = percentage(
                strongest.stream().filter(ranked ->
                        cited.contains(ranked.evidenceId())).count(),
                strongest.size());
        long claimableRecords = catalog.records().stream()
                .filter(record -> !record.evidenceId().startsWith("JOB."))
                .filter(record -> !record.evidenceId().startsWith("REQUEST."))
                .count();
        int coverage = percentage(cited.stream()
                .filter(id -> !id.startsWith("JOB."))
                .filter(id -> !id.startsWith("REQUEST."))
                .count(), claimableRecords);

        List<Integer> orderedScores = candidateRankings.stream()
                .map(RankedEvidence::score)
                .sorted()
                .toList();
        int weakBoundary = orderedScores.isEmpty()
                ? Integer.MIN_VALUE
                : orderedScores.get(Math.max(0, orderedScores.size() / 4));
        long weakCitations = cited.stream()
                .filter(scoreByEvidence::containsKey)
                .filter(id -> scoreByEvidence.get(id) <= weakBoundary)
                .count();
        int weakOveruse = cited.isEmpty()
                ? 100
                : clamp(100 - percentage(weakCitations, cited.size()));

        int relevance = relevanceScore(cited, scoreByEvidence);
        int summary = summaryScore(documents.getCv(), catalog);
        int bullets = bulletScore(documents.getCv());
        int complementarity = complementarityScore(
                documents.getCv(), documents.getCoverLetter());
        int readability = readabilityScore(documents);
        int concision = concisionScore(documents);
        int seniority = seniorityScore(documents.getCv(), catalog);
        int overall = weightedAverage(
                relevance, 18,
                strongUse, 16,
                summary, 14,
                bullets, 12,
                complementarity, 10,
                readability, 8,
                concision, 8,
                seniority, 8,
                weakOveruse, 6);

        List<String> findings = new ArrayList<>();
        addFinding(findings, relevance < 65,
                "Important vacancy concepts are not yet prominent enough.");
        addFinding(findings, strongUse < 70,
                "Some of the strongest ranked evidence is omitted or underused.");
        addFinding(findings, summary < 70,
                "The professional summary needs more specific vacancy fit.");
        addFinding(findings, complementarity < 75,
                "The CV and cover letter repeat too much of the same wording.");
        addFinding(findings, weakOveruse < 80,
                "Low-value evidence consumes space that stronger evidence could use.");
        if (findings.isEmpty()) {
            findings.add("No deterministic application-quality threshold failed.");
        }

        return new ApplicationQualityReport(
                VERSION,
                grounding,
                new Metrics(
                        relevance,
                        coverage,
                        strongUse,
                        weakOveruse,
                        complementarity,
                        summary,
                        bullets,
                        readability,
                        concision,
                        seniority,
                        overall),
                strongest.stream()
                        .filter(ranked -> cited.contains(ranked.evidenceId()))
                        .map(RankedEvidence::evidenceId)
                        .toList(),
                strongest.stream()
                        .filter(ranked -> !cited.contains(ranked.evidenceId()))
                        .map(RankedEvidence::evidenceId)
                        .toList(),
                List.copyOf(findings));
    }

    private int relevanceScore(
            Set<String> cited,
            Map<String, Integer> scores
    ) {
        List<Integer> used = cited.stream()
                .filter(scores::containsKey)
                .map(scores::get)
                .toList();
        if (used.isEmpty()) {
            return 0;
        }
        int maximum = scores.values().stream().max(Integer::compareTo).orElse(1);
        double average = used.stream().mapToInt(Integer::intValue).average().orElse(0);
        return clamp((int) Math.round(100 * average / Math.max(1, maximum)));
    }

    private int summaryScore(GeneratedCv cv, ClaimEvidenceCatalog catalog) {
        if (cv == null || !StringUtils.hasText(cv.getPersonalSummary())) {
            return 0;
        }
        String summary = cv.getPersonalSummary();
        int words = ApplicationQualityPlanner.wordCount(summary);
        int score = words >= 35 && words <= 110 ? 35 : 15;
        int concepts = planner.matchedConcepts(summary, catalog).size();
        score += Math.min(45, concepts * 12);
        String normalized = ApplicationQualityPlanner.normalize(summary);
        if (normalized.contains("application for")
                || normalized.contains("verified skills")
                || normalized.contains("canonical system data")
                || normalized.contains("waitlist foundations")) {
            score -= 35;
        }
        if (summary.split("[.!?]+\\s*").length >= 2) {
            score += 20;
        }
        return clamp(score);
    }

    private int bulletScore(GeneratedCv cv) {
        if (cv == null) {
            return 0;
        }
        List<String> bullets = safe(cv.getWorkHistory()).stream()
                .flatMap(work -> safe(work.getResponsibilities()).stream())
                .filter(StringUtils::hasText)
                .toList();
        if (bullets.isEmpty()) {
            return 0;
        }
        int specific = 0;
        for (String bullet : bullets) {
            int words = ApplicationQualityPlanner.wordCount(bullet);
            Set<String> terms = ApplicationQualityPlanner.terms(bullet);
            boolean action = terms.stream().anyMatch(Set.of(
                    "built", "developed", "delivered", "designed", "implemented",
                    "integrated", "improved", "owned", "tested", "supported",
                    "mentored", "maintained")::contains);
            if (action && words >= 5 && words <= 48) {
                specific++;
            }
        }
        return percentage(specific, bullets.size());
    }

    private int complementarityScore(
            GeneratedCv cv,
            GeneratedCoverLetter coverLetter
    ) {
        if (cv == null || coverLetter == null) {
            return 100;
        }
        List<String> cvUnits = new ArrayList<>();
        add(cvUnits, cv.getPersonalSummary());
        safe(cv.getWorkHistory()).forEach(work ->
                cvUnits.addAll(safe(work.getResponsibilities())));
        safe(cv.getProjects()).forEach(project -> {
            add(cvUnits, project.getDescription());
            cvUnits.addAll(safe(project.getHighlights()));
        });
        List<String> coverUnits = safe(coverLetter.getBodyParagraphs());
        double maximum = 0;
        for (String cover : coverUnits) {
            for (String cvUnit : cvUnits) {
                maximum = Math.max(maximum, jaccard(cover, cvUnit));
            }
        }
        return clamp((int) Math.round(100 * (1 - maximum)));
    }

    private int readabilityScore(GeneratedApplicationDocuments documents) {
        List<String> units = allNarrative(documents);
        if (units.isEmpty()) {
            return 0;
        }
        long readable = units.stream()
                .filter(text -> {
                    int words = ApplicationQualityPlanner.wordCount(text);
                    return words >= 3 && words <= 55;
                })
                .count();
        return percentage(readable, units.size());
    }

    private int concisionScore(GeneratedApplicationDocuments documents) {
        int cvWords = cvWords(documents.getCv());
        int coverWords = safe(documents.getCoverLetter() == null
                        ? null
                        : documents.getCoverLetter().getBodyParagraphs()).stream()
                .mapToInt(ApplicationQualityPlanner::wordCount)
                .sum();
        int cvScore = documents.getCv() == null
                ? 100
                : rangeScore(cvWords, 350, 1_000);
        int coverScore = documents.getCoverLetter() == null
                ? 100
                : rangeScore(coverWords, 100, 500);
        return (cvScore + coverScore) / 2;
    }

    private int seniorityScore(GeneratedCv cv, ClaimEvidenceCatalog catalog) {
        if (cv == null) {
            return 100;
        }
        String title = catalog.records().stream()
                .filter(record -> "JOB.TITLE".equals(record.evidenceId()))
                .map(ApprovedEvidenceRecord::value)
                .findFirst()
                .orElse("");
        String summary = ApplicationQualityPlanner.normalize(
                cv.getPersonalSummary());
        if (ApplicationQualityPlanner.normalize(title).contains("junior")
                && (summary.contains("chief executive")
                        || summary.contains("ceo")
                        || summary.contains("executive leader"))) {
            return 35;
        }
        return 100;
    }

    private List<String> allNarrative(GeneratedApplicationDocuments documents) {
        List<String> result = new ArrayList<>();
        GeneratedCv cv = documents.getCv();
        if (cv != null) {
            add(result, cv.getPersonalSummary());
            safe(cv.getWorkHistory()).forEach(work ->
                    result.addAll(safe(work.getResponsibilities())));
            safe(cv.getProjects()).forEach(project -> {
                add(result, project.getDescription());
                result.addAll(safe(project.getHighlights()));
            });
        }
        GeneratedCoverLetter cover = documents.getCoverLetter();
        if (cover != null) {
            result.addAll(safe(cover.getBodyParagraphs()));
        }
        return result.stream().filter(StringUtils::hasText).toList();
    }

    private int cvWords(GeneratedCv cv) {
        if (cv == null) {
            return 0;
        }
        int words = ApplicationQualityPlanner.wordCount(cv.getPersonalSummary());
        for (GeneratedWorkHistory work : safe(cv.getWorkHistory())) {
            words += safe(work.getResponsibilities()).stream()
                    .mapToInt(ApplicationQualityPlanner::wordCount)
                    .sum();
        }
        for (GeneratedProject project : safe(cv.getProjects())) {
            words += ApplicationQualityPlanner.wordCount(project.getDescription());
            words += safe(project.getHighlights()).stream()
                    .mapToInt(ApplicationQualityPlanner::wordCount)
                    .sum();
        }
        return words;
    }

    private double jaccard(String left, String right) {
        Set<String> leftTerms = ApplicationQualityPlanner.terms(left);
        Set<String> rightTerms = ApplicationQualityPlanner.terms(right);
        if (leftTerms.isEmpty() || rightTerms.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(leftTerms);
        intersection.retainAll(rightTerms);
        Set<String> union = new HashSet<>(leftTerms);
        union.addAll(rightTerms);
        return (double) intersection.size() / union.size();
    }

    private int rangeScore(int value, int minimum, int maximum) {
        if (value >= minimum && value <= maximum) {
            return 100;
        }
        if (value < minimum) {
            return clamp((int) Math.round(100.0 * value / minimum));
        }
        return clamp((int) Math.round(100.0 * maximum / value));
    }

    private int weightedAverage(int... valuesAndWeights) {
        int weighted = 0;
        int totalWeight = 0;
        for (int index = 0; index < valuesAndWeights.length; index += 2) {
            weighted += valuesAndWeights[index] * valuesAndWeights[index + 1];
            totalWeight += valuesAndWeights[index + 1];
        }
        return totalWeight == 0 ? 0 : clamp(weighted / totalWeight);
    }

    private int percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return 100;
        }
        return clamp((int) Math.round(100.0 * numerator / denominator));
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private void addFinding(
            List<String> findings,
            boolean condition,
            String finding
    ) {
        if (condition) {
            findings.add(finding);
        }
    }

    private void add(List<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value);
        }
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
