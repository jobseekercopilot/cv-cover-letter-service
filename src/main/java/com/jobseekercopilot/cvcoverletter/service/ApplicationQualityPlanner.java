package com.jobseekercopilot.cvcoverletter.service;

import com.jobseekercopilot.cvcoverletter.model.ApplicationQualityPlan;
import com.jobseekercopilot.cvcoverletter.model.ApplicationQualityPlan.RankedEvidence;
import com.jobseekercopilot.cvcoverletter.model.ApplicationQualityPlan.VacancyEmphasis;
import com.jobseekercopilot.cvcoverletter.model.ApprovedEvidenceRecord;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Produces a bounded, explainable application-quality plan without making new
 * candidate claims. It deliberately combines semantic concept matching with
 * evidence strength, specificity and space cost instead of relying on raw
 * keyword counts alone.
 */
public final class ApplicationQualityPlanner {
    public static final String VERSION = "application-quality-1.0.0";
    private static final Pattern MARKUP = Pattern.compile("<[^>]+>");
    private static final Pattern TERM_SEPARATOR =
            Pattern.compile("[^\\p{L}\\p{N}#+.]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "and", "are", "but", "for", "from", "have", "into", "our",
            "that", "the", "their", "this", "will", "with", "you", "your",
            "role", "work", "working", "experience", "skills", "strong",
            "candidate", "responsible", "including", "using");
    private static final Set<String> ACTION_TERMS = Set.of(
            "built", "build", "developed", "develop", "delivered", "deliver",
            "shipped", "ship", "launched", "launch", "designed", "design",
            "implemented", "integrated", "improved", "owned", "led", "tested",
            "maintained", "supported", "mentored", "collaborated", "authored");
    private static final Set<String> OUTCOME_TERMS = Set.of(
            "production", "customer", "users", "reliable", "safety-critical",
            "accessible", "functioning", "improved", "launch", "shipped",
            "delivered", "availability", "performance");
    private static final List<String> LOW_SIGNAL_INVENTORY = List.of(
            "canonical system data", "system data personas", "waitlist",
            "ai-credit architecture", "payment architecture", "seed data",
            "fixture", "internal service", "gateway inventory");

    private static final List<ConceptRule> CONCEPTS = List.of(
            concept("ANGULAR_FRONTEND", "CORE_TECHNICAL", 18,
                    "angular", "typescript", "javascript", "front end",
                    "frontend", "user interface", "customer-facing"),
            concept("JAVA_BACKEND", "CORE_TECHNICAL", 18,
                    "java", "spring", "spring boot", "backend", "microservice",
                    "rest api", "restful", "server-side"),
            concept("PYTHON_ENGINEERING", "CORE_TECHNICAL", 13,
                    "python", "django", "flask", "fastapi"),
            concept("FULL_STACK", "CORE_TECHNICAL", 16,
                    "full stack", "full-stack", "end to end", "end-to-end"),
            concept("PRODUCT_DELIVERY", "RESPONSIBILITY", 17,
                    "production feature", "shipping", "ship", "launch",
                    "product development", "platform improvement", "deliver"),
            concept("INTEGRATIONS", "RESPONSIBILITY", 15,
                    "partner integration", "third party", "third-party",
                    "integration", "api", "openapi"),
            concept("OWNERSHIP_AUTONOMY", "BEHAVIOUR", 16,
                    "ownership", "autonomy", "proactive", "technical decision",
                    "independent", "take initiative"),
            concept("COLLABORATION_COMMUNICATION", "BEHAVIOUR", 14,
                    "cross-functional", "collaboration", "collaborate",
                    "communication", "product team", "commercial", "data team",
                    "stakeholder"),
            concept("PROBLEM_SOLVING_LEARNING", "BEHAVIOUR", 14,
                    "problem solving", "engineering fundamentals", "learn quickly",
                    "learning", "adapt", "troubleshoot"),
            concept("SOFTWARE_QUALITY", "CORE_TECHNICAL", 14,
                    "automated testing", "unit testing", "integration testing",
                    "end-to-end testing", "playwright", "quality", "reliable"),
            concept("CLOUD_DEVOPS", "NICE_TO_HAVE", 12,
                    "aws", "cloud", "docker", "linux", "ci/cd", "devops",
                    "deployment", "infrastructure"),
            concept("TECHNICAL_SUPPORT", "RESPONSIBILITY", 12,
                    "application support", "technical support", "incident",
                    "troubleshoot", "customer support", "service desk"),
            concept("MENTORING_LEADERSHIP", "BEHAVIOUR", 9,
                    "mentor", "mentoring", "leadership", "interviewing",
                    "buddy", "coach", "apprentice"),
            concept("JUNIOR_GROWTH", "SENIORITY", 11,
                    "junior", "graduate", "early career", "learn quickly",
                    "fundamentals", "development opportunity"),
            concept("INSURANCE_DOMAIN", "DOMAIN", 7,
                    "insurance", "insurtech", "financial services"),
            concept("SUSTAINABLE_BUSINESS", "CULTURE", 5,
                    "b-corp", "b corp", "sustainability", "fairness",
                    "carbon reduction"));

    public ApplicationQualityPlan plan(ClaimEvidenceCatalog catalog) {
        String advert = evidenceValue(catalog.records(), "JOB.DESCRIPTION");
        String title = evidenceValue(catalog.records(), "JOB.TITLE");
        String vacancyText = title + " " + advert;
        List<ActiveConcept> activeConcepts = activeConcepts(vacancyText);
        List<RankedEvidence> ranked = catalog.records().stream()
                .filter(record -> !record.evidenceId().startsWith("JOB."))
                .filter(record -> !record.evidenceId().startsWith("REQUEST."))
                .map(record -> rank(record, vacancyText, activeConcepts))
                .sorted(Comparator
                        .comparingInt(RankedEvidence::score)
                        .reversed()
                        .thenComparing(RankedEvidence::evidenceId))
                .toList();
        return new ApplicationQualityPlan(
                VERSION,
                activeConcepts.stream()
                        .map(ActiveConcept::toPublic)
                        .toList(),
                ranked,
                List.of(
                        "Prioritise strong vacancy-relevant evidence over volume.",
                        "Lead with professional delivery, responsibility and outcomes.",
                        "Compress low-relevance evidence without creating false chronology.",
                        "Keep CV and cover letter complementary rather than repetitive.",
                        "Prefer concise proof over architecture or subsystem inventory."));
    }

    public int score(
            ApprovedEvidenceRecord record,
            ClaimEvidenceCatalog catalog
    ) {
        String vacancyText = evidenceValue(catalog.records(), "JOB.TITLE")
                + " " + evidenceValue(catalog.records(), "JOB.DESCRIPTION");
        return rank(record, vacancyText, activeConcepts(vacancyText)).score();
    }

    public List<String> matchedConcepts(
            String evidence,
            ClaimEvidenceCatalog catalog
    ) {
        String vacancyText = evidenceValue(catalog.records(), "JOB.TITLE")
                + " " + evidenceValue(catalog.records(), "JOB.DESCRIPTION");
        String normalizedEvidence = normalize(evidence);
        return activeConcepts(vacancyText).stream()
                .filter(active -> active.rule().matches(normalizedEvidence))
                .map(active -> active.rule().name())
                .toList();
    }

    private RankedEvidence rank(
            ApprovedEvidenceRecord record,
            String vacancyText,
            List<ActiveConcept> activeConcepts
    ) {
        String value = record.value() == null ? "" : record.value();
        String normalized = normalize(value);
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        int score = 0;

        for (ActiveConcept active : activeConcepts) {
            if (active.rule().matches(normalized)) {
                score += active.priority();
                reasons.add("supports " + active.rule().name());
            }
        }

        Set<String> vacancyTerms = terms(vacancyText);
        long overlap = terms(value).stream().filter(vacancyTerms::contains).count();
        if (overlap > 0) {
            score += Math.min(18, (int) overlap * 3);
            reasons.add("shares " + overlap + " material vacancy term"
                    + (overlap == 1 ? "" : "s"));
        }

        int evidenceStrength = evidenceStrength(record);
        score += evidenceStrength;
        if (evidenceStrength >= 9) {
            reasons.add("strong evidence type");
        }
        if ("EMPLOYMENT".equals(record.category())) {
            score += 10;
            reasons.add("professional employment evidence");
        } else if ("PROJECT".equals(record.category())) {
            score += 7;
            reasons.add("project delivery evidence");
        } else if ("ACHIEVEMENT".equals(record.category())) {
            score += 6;
        }

        Set<String> evidenceTerms = terms(value);
        if (evidenceTerms.stream().anyMatch(ACTION_TERMS::contains)) {
            score += 7;
            reasons.add("describes action or responsibility");
        }
        if (evidenceTerms.stream().anyMatch(OUTCOME_TERMS::contains)) {
            score += 5;
            reasons.add("describes delivery or beneficiary context");
        }
        int words = wordCount(value);
        if (words >= 5 && words <= 34) {
            score += 5;
            reasons.add("specific and concise");
        } else if (words > 50) {
            int penalty = Math.min(18, 3 + ((words - 50) / 8));
            score -= penalty;
            reasons.add("high space cost");
        }
        for (String inventoryTerm : LOW_SIGNAL_INVENTORY) {
            if (normalized.contains(inventoryTerm)
                    && !normalize(vacancyText).contains(inventoryTerm)) {
                score -= 12;
                reasons.add("low hiring-decision signal for this vacancy");
            }
        }
        return new RankedEvidence(
                record.evidenceId(),
                Math.max(-50, score),
                List.copyOf(reasons));
    }

    private int evidenceStrength(ApprovedEvidenceRecord record) {
        return switch (record.factType() == null ? "" : record.factType()) {
            case "ACHIEVEMENTS" -> 14;
            case "RESPONSIBILITIES" -> 12;
            case "DEMONSTRATED_SKILL" -> 11;
            case "DESCRIPTION" -> 9;
            case "ROLE_TITLE", "JOB_TITLE", "PROJECT_ROLE" -> 7;
            case "QUALIFICATION_TITLE", "PROGRAMME_OR_SUBJECT" -> 6;
            case "DECLARED_SKILL" -> 3;
            default -> 2;
        };
    }

    private List<ActiveConcept> activeConcepts(String vacancyText) {
        String normalized = normalize(vacancyText);
        List<ActiveConcept> active = new ArrayList<>();
        for (ConceptRule rule : CONCEPTS) {
            List<String> matched = rule.terms().stream()
                    .filter(normalized::contains)
                    .toList();
            if (!matched.isEmpty()) {
                int priority = rule.weight() + Math.min(6, matched.size() * 2);
                active.add(new ActiveConcept(rule, priority, matched));
            }
        }
        return active.stream()
                .sorted(Comparator
                        .comparingInt(ActiveConcept::priority)
                        .reversed()
                        .thenComparing(activeConcept ->
                                activeConcept.rule().name()))
                .toList();
    }

    private static String evidenceValue(
            List<ApprovedEvidenceRecord> records,
            String evidenceId
    ) {
        return records.stream()
                .filter(record -> evidenceId.equals(record.evidenceId()))
                .map(ApprovedEvidenceRecord::value)
                .findFirst()
                .orElse("");
    }

    static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return MARKUP.matcher(value)
                .replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&#163;", " ")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9#+.]+", " ")
                .trim();
    }

    static Set<String> terms(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Arrays.stream(TERM_SEPARATOR.split(normalize(value)))
                .filter(term -> term.length() >= 3)
                .filter(term -> !STOP_WORDS.contains(term))
                .forEach(result::add);
        return result;
    }

    static int wordCount(String value) {
        return StringUtils.hasText(value)
                ? value.trim().split("\\s+").length
                : 0;
    }

    private static ConceptRule concept(
            String name,
            String category,
            int weight,
            String... terms
    ) {
        return new ConceptRule(
                name,
                category,
                weight,
                List.of(terms).stream().map(ApplicationQualityPlanner::normalize).toList());
    }

    private record ConceptRule(
            String name,
            String category,
            int weight,
            List<String> terms
    ) {
        private boolean matches(String value) {
            return terms.stream().anyMatch(value::contains);
        }
    }

    private record ActiveConcept(
            ConceptRule rule,
            int priority,
            List<String> matchedTerms
    ) {
        private VacancyEmphasis toPublic() {
            return new VacancyEmphasis(
                    rule.name(), rule.category(), priority, matchedTerms);
        }
    }
}
