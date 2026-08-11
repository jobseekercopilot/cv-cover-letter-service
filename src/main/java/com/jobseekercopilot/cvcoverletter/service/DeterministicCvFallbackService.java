package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobseekercopilot.cvcoverletter.model.ApprovedEvidenceRecord;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import com.jobseekercopilot.cvcoverletter.model.EvidencePurpose;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds a conservative CV from already approved evidence. It deliberately
 * performs no semantic embellishment: canonical fields and narrative bullets
 * are copied from evidence. Ordering and the short summary are deterministically
 * tailored from lexical overlap between the complete advert and approved evidence.
 */
@Component
public class DeterministicCvFallbackService {
    static final String FALLBACK_VERSION = "deterministic-evidence-cv-1.1.0";
    private static final int MAX_SKILLS = 12;
    private static final int MAX_EMPLOYMENT = 12;
    private static final int MAX_QUALIFICATIONS = 8;
    private static final int MAX_PROJECTS = 4;
    private static final Pattern MARKUP = Pattern.compile("<[^>]+>");
    private static final Pattern TERM_SEPARATOR =
            Pattern.compile("[^\\p{L}\\p{N}#+.]+");
    private static final Pattern FOUR_DIGIT_YEAR =
            Pattern.compile("(?<!\\d)(19|20)\\d{2}(?!\\d)");
    private static final Set<String> STOP_WORDS = Set.of(
            "and", "are", "but", "for", "from", "have", "into", "our",
            "that", "the", "their", "this", "will", "with", "you", "your",
            "role", "work", "working", "experience", "skills", "strong");

    private final ObjectMapper objectMapper;

    public DeterministicCvFallbackService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(
            NormalizedGenerationInput input,
            ClaimEvidenceCatalog evidenceCatalog) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(evidenceCatalog, "evidenceCatalog");
        EvidenceIndex evidence = new EvidenceIndex(evidenceCatalog.records());
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode cv = root.putObject("cv");
        ArrayNode claims = root.putArray("claims");
        ClaimSequence claimSequence = new ClaimSequence(claims);

        cv.put("title", "Curriculum Vitae");
        cv.put("targetRole", input.job().title());
        claimSequence.add(
                "SUPPORTED",
                List.of(evidence.requiredId("JOB.TITLE")),
                "/cv/targetRole");

        TailoredSummary summary = tailoredSummary(input, evidence);
        cv.put("personalSummary", summary.text());
        ObjectNode summaryClaim = root.putObject("personalSummaryClaim");
        summaryClaim.put("claimId", "CLAIM-9003");
        summaryClaim.put("disposition", "REWORDED");
        ArrayNode summaryEvidence = summaryClaim.putArray("evidenceIds");
        summary.evidenceIds().forEach(summaryEvidence::add);
        summaryClaim.put("contentPath", "/cv/personalSummary");
        summaryClaim.put("reviewText", "");

        addSkills(cv, input, evidence);
        addEmployment(cv, input, evidence, claimSequence);
        addQualifications(cv, input, evidence, claimSequence);
        addProjects(cv, evidence, claimSequence);

        ObjectNode notes = root.putObject("generationNotes");
        notes.putArray("assumptionsMade");
        notes.putArray("missingInformation");
        notes.put(
                "tailoringSummary",
                "A conservative evidence-based CV was produced because model generation did not yield an acceptable draft.");
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(
                    "Deterministic CV could not be serialised.", impossible);
        }
    }

    private void addSkills(
            ObjectNode cv,
            NormalizedGenerationInput input,
            EvidenceIndex evidence) {
        ArrayNode skills = cv.putArray("coreSkills");
        rankedSkills(input, evidence).stream()
                .limit(MAX_SKILLS)
                .forEach(skill -> {
                    ObjectNode item = skills.addObject();
                    item.put("name", skill.value());
                    item.put("evidence", "");
                });
    }

    private void addEmployment(
            ObjectNode cv,
            NormalizedGenerationInput input,
            EvidenceIndex evidence,
            ClaimSequence claims) {
        ArrayNode history = cv.putArray("workHistory");
        int count = Math.min(
                input.profile().employmentHistory().size(),
                MAX_EMPLOYMENT);
        for (int index = 0; index < count; index++) {
            var employment = input.profile().employmentHistory().get(index);
            if (!StringUtils.hasText(employment.jobTitle())
                    || !StringUtils.hasText(employment.employer())
                    || !StringUtils.hasText(employment.startDate())) {
                continue;
            }
            int outputIndex = history.size();
            String prefix = "PROFILE.EMPLOYMENT." + (index + 1) + ".";
            ObjectNode item = history.addObject();
            canonical(
                    item,
                    "jobTitle",
                    employment.jobTitle(),
                    evidence,
                    prefix + "JOB_TITLE",
                    claims,
                    "/cv/workHistory/" + outputIndex + "/jobTitle");
            canonical(
                    item,
                    "employer",
                    employment.employer(),
                    evidence,
                    prefix + "EMPLOYER",
                    claims,
                    "/cv/workHistory/" + outputIndex + "/employer");
            canonical(
                    item,
                    "startDate",
                    employment.startDate(),
                    evidence,
                    prefix + "START_DATE",
                    claims,
                    "/cv/workHistory/" + outputIndex + "/startDate");
            optionalCanonical(
                    item,
                    "endDate",
                    employment.endDate(),
                    evidence,
                    prefix + "END_DATE",
                    claims,
                    "/cv/workHistory/" + outputIndex + "/endDate");
            ArrayNode responsibilities = item.putArray("responsibilities");
            if (StringUtils.hasText(employment.responsibilities())
                    && evidence.contains(prefix + "RESPONSIBILITIES")) {
                narrative(
                        responsibilities,
                        employment.responsibilities(),
                        evidence.requiredId(prefix + "RESPONSIBILITIES"));
            }
            item.put("tailoredDescription", "");
        }
        if (history.size() < MAX_EMPLOYMENT) {
            addSnapshotEmployment(history, evidence, claims);
        }
    }

    private void addSnapshotEmployment(
            ArrayNode history,
            EvidenceIndex evidence,
            ClaimSequence claims) {
        List<EvidenceGroup> groups = evidence.groups(record ->
                record.purpose() == EvidencePurpose.CV
                        && List.of("EMPLOYMENT", "FREELANCE")
                                .contains(record.category())).stream()
                .sorted(Comparator
                        .comparingInt(EvidenceGroup::startDateRank)
                        .reversed()
                        .thenComparing(EvidenceGroup::key))
                .toList();
        Set<String> detailedGroups = groups.stream()
                .sorted(Comparator
                        .comparingInt((EvidenceGroup group) ->
                                relevance(group.records(), evidence.jobText()))
                        .reversed()
                        .thenComparing(EvidenceGroup::key))
                .limit(2)
                .map(EvidenceGroup::key)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        for (EvidenceGroup group : groups) {
            if (history.size() >= MAX_EMPLOYMENT) {
                return;
            }
            ApprovedEvidenceRecord title = group.first(
                    "ROLE_TITLE", "JOB_TITLE", "HEADING");
            ApprovedEvidenceRecord employer = group.first(
                    "ORGANISATION", "ORGANISATION_CONTEXT");
            ApprovedEvidenceRecord start = group.first("START_DATE");
            if (title == null || employer == null || start == null) {
                continue;
            }
            int outputIndex = history.size();
            ObjectNode item = history.addObject();
            evidenceField(item, "jobTitle", title, claims,
                    "/cv/workHistory/" + outputIndex + "/jobTitle");
            evidenceField(item, "employer", employer, claims,
                    "/cv/workHistory/" + outputIndex + "/employer");
            evidenceField(item, "startDate", start, claims,
                    "/cv/workHistory/" + outputIndex + "/startDate");
            projectField(item, "endDate", group.first("END_DATE"), claims,
                    "/cv/workHistory/" + outputIndex + "/endDate");
            ArrayNode responsibilities = item.putArray("responsibilities");
            int narrativeLimit = detailedGroups.contains(group.key()) ? 3 : 1;
            group.records().stream()
                    .filter(record -> factTypeIn(
                            record,
                            "RESPONSIBILITIES",
                            "ACHIEVEMENTS",
                            "DESCRIPTION"))
                    .sorted(Comparator
                            .comparingInt((ApprovedEvidenceRecord record) ->
                                    relevance(record.value(), evidence.jobText()))
                            .reversed()
                            .thenComparing(ApprovedEvidenceRecord::factType))
                    .limit(narrativeLimit)
                    .forEach(record -> narrative(
                            responsibilities,
                            record.value(),
                            record.evidenceId()));
            item.put("tailoredDescription", "");
        }
    }

    private void addQualifications(
            ObjectNode cv,
            NormalizedGenerationInput input,
            EvidenceIndex evidence,
            ClaimSequence claims) {
        ArrayNode qualifications = cv.putArray("qualifications");
        int count = Math.min(
                input.profile().qualifications().size(),
                MAX_QUALIFICATIONS);
        for (int index = 0; index < count; index++) {
            var qualification = input.profile().qualifications().get(index);
            if (!StringUtils.hasText(qualification.qualificationName())) {
                continue;
            }
            int outputIndex = qualifications.size();
            String prefix = "PROFILE.QUALIFICATION." + (index + 1) + ".";
            ObjectNode item = qualifications.addObject();
            canonical(
                    item,
                    "qualificationName",
                    qualification.qualificationName(),
                    evidence,
                    prefix + "NAME",
                    claims,
                    "/cv/qualifications/" + outputIndex
                            + "/qualificationName");
            optionalCanonical(item, "issuingBody", qualification.issuingBody(),
                    evidence, prefix + "ISSUING_BODY", claims,
                    "/cv/qualifications/" + outputIndex + "/issuingBody");
            optionalCanonical(item, "status", value(qualification.status()),
                    evidence, prefix + "STATUS", claims,
                    "/cv/qualifications/" + outputIndex + "/status");
            optionalCanonical(item, "grade", qualification.grade(), evidence,
                    prefix + "GRADE", claims,
                    "/cv/qualifications/" + outputIndex + "/grade");
            optionalCanonical(item, "dateAchieved", qualification.dateAchieved(),
                    evidence, prefix + "DATE_ACHIEVED", claims,
                    "/cv/qualifications/" + outputIndex + "/dateAchieved");
            optionalCanonical(item, "expectedCompletion",
                    qualification.expectedCompletion(), evidence,
                    prefix + "EXPECTED_COMPLETION", claims,
                    "/cv/qualifications/" + outputIndex
                            + "/expectedCompletion");
        }
        if (qualifications.size() < MAX_QUALIFICATIONS) {
            addSnapshotQualifications(qualifications, evidence, claims);
        }
    }

    private void addSnapshotQualifications(
            ArrayNode qualifications,
            EvidenceIndex evidence,
            ClaimSequence claims) {
        for (EvidenceGroup group : evidence.groups(record ->
                record.purpose() == EvidencePurpose.CV
                        && List.of("EDUCATION", "QUALIFICATION_TRAINING")
                                .contains(record.category()))) {
            if (qualifications.size() >= MAX_QUALIFICATIONS) {
                return;
            }
            ApprovedEvidenceRecord name = group.first(
                    "QUALIFICATION_TITLE", "PROGRAMME_OR_SUBJECT");
            if (name == null) {
                continue;
            }
            int outputIndex = qualifications.size();
            ObjectNode item = qualifications.addObject();
            evidenceField(item, "qualificationName", name, claims,
                    "/cv/qualifications/" + outputIndex
                            + "/qualificationName");
            projectField(item, "issuingBody", group.first(
                    "ISSUER", "INSTITUTION"), claims,
                    "/cv/qualifications/" + outputIndex + "/issuingBody");
            ApprovedEvidenceRecord status = group.first(
                    "RESULT_OR_STATUS", "STATUS");
            projectField(item, "status", status, claims,
                    "/cv/qualifications/" + outputIndex + "/status");
            ApprovedEvidenceRecord grade = group.first(
                    "RESULT", "ACHIEVEMENTS");
            if (grade != null && status != null
                    && normalise(grade.value()).equals(
                            normalise(status.value()))) {
                grade = null;
            }
            projectField(item, "grade", grade, claims,
                    "/cv/qualifications/" + outputIndex + "/grade");
            projectField(item, "dateAchieved", group.first(
                    "ISSUE_DATE"), claims,
                    "/cv/qualifications/" + outputIndex + "/dateAchieved");
            item.put("expectedCompletion", "");
        }
    }

    private void evidenceField(
            ObjectNode target,
            String field,
            ApprovedEvidenceRecord evidence,
            ClaimSequence claims,
            String path) {
        target.put(field, evidence.value());
        claims.add("SUPPORTED", List.of(evidence.evidenceId()), path);
    }

    private void addProjects(
            ObjectNode cv,
            EvidenceIndex evidence,
            ClaimSequence claims) {
        ArrayNode projects = cv.putArray("projects");
        for (EvidenceGroup group : evidence.groups(record ->
                record.purpose() != EvidencePurpose.COVER_LETTER
                        && ("PROJECT".equals(record.category())
                                || "ACHIEVEMENT".equals(
                                        record.category()))).stream()
                .sorted(Comparator
                        .comparingInt((EvidenceGroup group) ->
                                relevance(group.records(), evidence.jobText()))
                        .reversed()
                        .thenComparing(EvidenceGroup::key))
                .limit(MAX_PROJECTS)
                .toList()) {
            ApprovedEvidenceRecord title = group.first(
                    "HEADING", "PROJECT_NAME", "ROLE_TITLE");
            ApprovedEvidenceRecord description = group.first(
                    "DESCRIPTION", "ACHIEVEMENTS", "RESPONSIBILITIES");
            if (title == null || description == null) {
                continue;
            }
            int outputIndex = projects.size();
            ObjectNode project = projects.addObject();
            project.put("title", title.value());
            claims.add("SUPPORTED", List.of(title.evidenceId()),
                    "/cv/projects/" + outputIndex + "/title");
            projectField(project, "role", group.first("PROJECT_ROLE"), claims,
                    "/cv/projects/" + outputIndex + "/role");
            projectField(project, "context", group.first(
                    "ORGANISATION_CONTEXT", "EMPLOYER", "ISSUER"), claims,
                    "/cv/projects/" + outputIndex + "/context");
            projectField(project, "startDate", group.first("START_DATE"), claims,
                    "/cv/projects/" + outputIndex + "/startDate");
            projectField(project, "endDate", group.first("END_DATE"), claims,
                    "/cv/projects/" + outputIndex + "/endDate");
            project.put("description", description.value());
            claims.add("SUPPORTED", List.of(description.evidenceId()),
                    "/cv/projects/" + outputIndex + "/description");
            ArrayNode highlights = project.putArray("highlights");
            group.records().stream()
                    .filter(record -> List.of(
                            "ACHIEVEMENTS", "RESPONSIBILITIES")
                            .contains(record.factType()))
                    .filter(record -> !normalise(record.value()).equals(
                            normalise(description.value())))
                    .limit(4)
                    .forEach(record -> narrative(
                            highlights,
                            record.value(),
                            record.evidenceId()));
        }
    }

    private void projectField(
            ObjectNode target,
            String field,
            ApprovedEvidenceRecord evidence,
            ClaimSequence claims,
            String path) {
        if (evidence == null) {
            target.put(field, "");
            return;
        }
        target.put(field, evidence.value());
        claims.add("SUPPORTED", List.of(evidence.evidenceId()), path);
    }

    private void canonical(
            ObjectNode target,
            String field,
            String value,
            EvidenceIndex evidence,
            String evidenceId,
            ClaimSequence claims,
            String path) {
        target.put(field, value.trim());
        claims.add(
                "SUPPORTED",
                List.of(evidence.requiredId(evidenceId)),
                path);
    }

    private void optionalCanonical(
            ObjectNode target,
            String field,
            String value,
            EvidenceIndex evidence,
            String evidenceId,
            ClaimSequence claims,
            String path) {
        if (!StringUtils.hasText(value) || !evidence.contains(evidenceId)) {
            target.put(field, "");
            return;
        }
        canonical(
                target,
                field,
                value,
                evidence,
                evidenceId,
                claims,
                path);
    }

    private void narrative(
            ArrayNode target,
            String text,
            String evidenceId) {
        String cleaned = text.trim()
                .replaceAll(
                        "(?i)\\s+without claiming\\s+[^.]*\\.?",
                        ".")
                .replaceAll("\\s+", " ")
                .trim();
        ObjectNode item = target.addObject();
        item.put("text", cleaned);
        item.put(
                "disposition",
                cleaned.equals(text.trim()) ? "SUPPORTED" : "REWORDED");
        item.putArray("evidenceIds").add(evidenceId);
    }

    private String value(Object value) {
        return value == null ? null : value.toString();
    }

    private static String normalise(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", " ")
                        .trim();
    }

    private TailoredSummary tailoredSummary(
            NormalizedGenerationInput input,
            EvidenceIndex evidence) {
        List<SkillEvidence> skills = rankedSkills(input, evidence);
        List<EvidenceGroup> employment = evidence.groups(record ->
                record.purpose() == EvidencePurpose.CV
                        && "EMPLOYMENT".equals(record.category())).stream()
                .sorted(Comparator
                        .comparingInt((EvidenceGroup group) ->
                                relevance(group.records(), evidence.jobText()))
                        .reversed()
                        .thenComparing(EvidenceGroup::key))
                .toList();
        EvidenceGroup primary = employment.isEmpty() ? null : employment.get(0);
        ApprovedEvidenceRecord role = primary == null
                ? null
                : primary.first("ROLE_TITLE", "HEADING", "JOB_TITLE");
        List<SkillEvidence> selectedSkills = skills.stream()
                .filter(SkillEvidence::claimable)
                .filter(skill -> relevance(skill.value(), evidence.jobText()) > 0)
                .limit(3)
                .toList();
        if (selectedSkills.size() < 2) {
            selectedSkills = skills.stream()
                    .filter(SkillEvidence::claimable)
                    .limit(3)
                    .toList();
        }

        List<String> sentences = new ArrayList<>();
        LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
        if (role != null) {
            StringBuilder opening = new StringBuilder(role.value().trim());
            evidenceIds.add(role.evidenceId());
            if (!selectedSkills.isEmpty()) {
                opening.append(" with experience in ")
                        .append(humanList(selectedSkills.stream()
                                .map(SkillEvidence::value)
                                .toList()));
                selectedSkills.forEach(skill ->
                        evidenceIds.add(skill.evidenceId()));
            }
            opening.append('.');
            sentences.add(opening.toString());
        } else if (!selectedSkills.isEmpty()) {
            sentences.add("Software professional with experience in "
                    + humanList(selectedSkills.stream()
                            .map(SkillEvidence::value)
                            .toList())
                    + " for the exact " + input.job().title() + " role.");
            selectedSkills.forEach(skill ->
                    evidenceIds.add(skill.evidenceId()));
        } else {
            sentences.add("Candidate evidence selected for the exact "
                    + input.job().title()
                    + " role, with no extension beyond confirmed facts.");
        }
        for (EvidenceGroup group : employment) {
            ApprovedEvidenceRecord narrative = group.records().stream()
                    .filter(record -> factTypeIn(
                            record,
                            "DESCRIPTION",
                            "RESPONSIBILITIES",
                            "ACHIEVEMENTS"))
                    .sorted(Comparator
                            .comparingInt((ApprovedEvidenceRecord record) ->
                                    relevance(record.value(), evidence.jobText()))
                            .reversed()
                            .thenComparing(ApprovedEvidenceRecord::factType))
                    .findFirst()
                    .orElse(null);
            if (narrative == null) {
                continue;
            }
            String sentence = firstSentence(narrative.value());
            if (!sentence.isBlank()
                    && sentences.stream().noneMatch(existing ->
                            normalise(existing).equals(normalise(sentence)))) {
                sentences.add(sentence);
                evidenceIds.add(narrative.evidenceId());
            }
            if (sentences.size() == 3) {
                break;
            }
        }
        if (sentences.size() < 2) {
            String skillText = selectedSkills.isEmpty()
                    ? input.job().title()
                    : humanList(selectedSkills.stream()
                            .map(SkillEvidence::value)
                            .toList());
            List<String> jobFocus = orderedTerms(evidence.jobText()).stream()
                    .limit(3)
                    .toList();
            sentences.add("Brings "
                    + skillText
                    + " relevant to the role's priorities"
                    + (jobFocus.isEmpty()
                            ? ""
                            : ", including " + humanList(jobFocus))
                    + ", for the exact "
                    + input.job().title()
                    + " vacancy.");
        }
        evidenceIds.add(evidence.requiredId("REQUEST.GENERATION_INTENT"));
        evidenceIds.add(evidence.requiredId("JOB.TITLE"));
        evidenceIds.add(evidence.requiredId("JOB.DESCRIPTION"));
        if (evidenceIds.stream().noneMatch(evidence::isClaimantEvidence)) {
            evidenceIds.add(evidence.firstClaimantEvidenceId());
        }
        return new TailoredSummary(
                String.join(" ", sentences.stream().limit(4).toList()),
                List.copyOf(evidenceIds.stream().limit(12).toList()));
    }

    private List<SkillEvidence> rankedSkills(
            NormalizedGenerationInput input,
            EvidenceIndex evidence) {
        Map<String, SkillEvidence> distinct = new LinkedHashMap<>();
        input.profile().skills().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(skill -> {
                    ApprovedEvidenceRecord record = evidence.skill(skill);
                    if (record != null) {
                        distinct.putIfAbsent(
                                normalise(skill),
                                new SkillEvidence(
                                        skill,
                                        record.evidenceId(),
                                        "DEMONSTRATED_SKILL".equals(
                                                record.factType())));
                    }
                });
        return distinct.values().stream()
                .sorted(Comparator
                        .comparingInt((SkillEvidence skill) ->
                                relevance(skill.value(), evidence.jobText()))
                        .reversed()
                        .thenComparingInt(skill -> input.profile().skills()
                                .indexOf(skill.value())))
                .toList();
    }

    private static int relevance(
            List<ApprovedEvidenceRecord> records,
            String jobText) {
        return records.stream()
                .mapToInt(record -> relevance(record.value(), jobText))
                .sum();
    }

    private static int relevance(String value, String jobText) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(jobText)) {
            return 0;
        }
        String normalizedValue = normalise(value);
        String normalizedJob = normalise(MARKUP.matcher(jobText)
                .replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&#163;", " "));
        int score = (" " + normalizedJob + " ").contains(
                        " " + normalizedValue + " ")
                ? 40
                : 0;
        Set<String> jobTerms = terms(normalizedJob);
        for (String term : terms(normalizedValue)) {
            if (jobTerms.contains(term)) {
                score += 3;
            }
        }
        return score;
    }

    private static Set<String> terms(String value) {
        Set<String> terms = new HashSet<>();
        for (String term : TERM_SEPARATOR.split(normalise(value))) {
            if (term.length() >= 3 && !STOP_WORDS.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static List<String> orderedTerms(String value) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        for (String term : TERM_SEPARATOR.split(normalise(
                MARKUP.matcher(value).replaceAll(" ")))) {
            if (term.length() >= 3 && !STOP_WORDS.contains(term)) {
                terms.add(term);
            }
        }
        return List.copyOf(terms);
    }

    private String firstSentence(String value) {
        String text = value == null ? "" : value.trim();
        int boundary = text.indexOf(". ");
        String sentence = boundary < 0 ? text : text.substring(0, boundary + 1);
        if (!sentence.isBlank() && !sentence.matches(".*[.!?]$")) {
            sentence += ".";
        }
        return sentence;
    }

    private String humanList(List<String> values) {
        if (values.isEmpty()) {
            return "";
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        return String.join(", ", values.subList(0, values.size() - 1))
                + " and " + values.get(values.size() - 1);
    }

    private static boolean factTypeIn(
            ApprovedEvidenceRecord record,
            String... factTypes) {
        if (record.factType() == null) {
            return false;
        }
        for (String factType : factTypes) {
            if (factType.equals(record.factType())) {
                return true;
            }
        }
        return false;
    }

    private record TailoredSummary(String text, List<String> evidenceIds) {
    }

    private record SkillEvidence(
            String value,
            String evidenceId,
            boolean claimable) {
    }

    private static final class ClaimSequence {
        private final ArrayNode claims;
        private int next = 1;

        private ClaimSequence(ArrayNode claims) {
            this.claims = claims;
        }

        private void add(
                String disposition,
                List<String> evidenceIds,
                String path) {
            ObjectNode claim = claims.addObject();
            claim.put("claimId", "CLAIM-" + String.format("%03d", next++));
            claim.put("disposition", disposition);
            ArrayNode evidence = claim.putArray("evidenceIds");
            evidenceIds.forEach(evidence::add);
            claim.putArray("contentPaths").add(path);
            claim.put("reviewText", "");
        }
    }

    private static final class EvidenceIndex {
        private final Map<String, ApprovedEvidenceRecord> byId;
        private final List<ApprovedEvidenceRecord> records;

        private EvidenceIndex(List<ApprovedEvidenceRecord> records) {
            this.records = List.copyOf(records);
            this.byId = new LinkedHashMap<>();
            records.forEach(record -> byId.put(record.evidenceId(), record));
        }

        private boolean contains(String id) {
            return byId.containsKey(id);
        }

        private String requiredId(String id) {
            if (!contains(id)) {
                throw new IllegalStateException(
                        "Deterministic CV required missing approved evidence "
                                + id + ".");
            }
            return id;
        }

        private String firstClaimantEvidenceId() {
            return records.stream()
                    .filter(record -> record.source()
                            == EvidenceSource.EVIDENCE_SNAPSHOT)
                    .filter(record -> record.purpose()
                            .supports(EvidencePurpose.CV))
                    .map(ApprovedEvidenceRecord::evidenceId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Deterministic CV requires at least one confirmed claimant evidence record."));
        }

        private boolean isClaimantEvidence(String id) {
            ApprovedEvidenceRecord record = byId.get(id);
            return record != null
                    && (record.source() == EvidenceSource.EVIDENCE_SNAPSHOT
                            || record.source()
                                    == EvidenceSource.PROFILE_REVISION
                            || record.source() == EvidenceSource.PROFILE);
        }

        private ApprovedEvidenceRecord skill(String value) {
            String normalized = normalise(value);
            return records.stream()
                    .filter(record -> record.purpose()
                            .supports(EvidencePurpose.CV))
                    .filter(record -> "DECLARED_SKILL".equals(
                                    record.factType())
                            || "DEMONSTRATED_SKILL".equals(
                                    record.factType()))
                    .filter(record -> normalise(record.value())
                            .equals(normalized))
                    .sorted(Comparator.comparingInt(record ->
                            "DEMONSTRATED_SKILL".equals(record.factType())
                                    ? 0 : 1))
                    .findFirst()
                    .orElse(null);
        }

        private String jobText() {
            return records.stream()
                    .filter(record -> "JOB.DESCRIPTION".equals(
                            record.evidenceId()))
                    .map(ApprovedEvidenceRecord::value)
                    .findFirst()
                    .orElse("");
        }

        private List<EvidenceGroup> groups(
                Predicate<ApprovedEvidenceRecord> predicate) {
            Map<String, List<ApprovedEvidenceRecord>> grouped =
                    new LinkedHashMap<>();
            records.stream().filter(predicate).forEach(record -> {
                String source = record.sourcePath();
                int facts = source.indexOf("/facts/");
                String key = facts < 0 ? source : source.substring(0, facts);
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(record);
            });
            return grouped.values().stream()
                    .map(EvidenceGroup::new)
                    .toList();
        }
    }

    private record EvidenceGroup(List<ApprovedEvidenceRecord> records) {
        private EvidenceGroup {
            records = List.copyOf(records);
        }

        private ApprovedEvidenceRecord first(String... factTypes) {
            for (String factType : factTypes) {
                for (ApprovedEvidenceRecord record : records) {
                    if (factType.equals(record.factType())) {
                        return record;
                    }
                }
            }
            return null;
        }

        private String key() {
            if (records.isEmpty()) {
                return "";
            }
            String source = records.get(0).sourcePath();
            int facts = source.indexOf("/facts/");
            return facts < 0 ? source : source.substring(0, facts);
        }

        private int startDateRank() {
            ApprovedEvidenceRecord start = first("START_DATE");
            if (start == null || !StringUtils.hasText(start.value())) {
                return 0;
            }
            String value = start.value().toLowerCase(Locale.ROOT);
            var yearMatcher = FOUR_DIGIT_YEAR.matcher(value);
            if (!yearMatcher.find()) {
                return 0;
            }
            int year = Integer.parseInt(yearMatcher.group());
            int month = 1;
            String[] months = {
                    "jan", "feb", "mar", "apr", "may", "jun",
                    "jul", "aug", "sep", "oct", "nov", "dec"
            };
            for (int index = 0; index < months.length; index++) {
                if (value.contains(months[index])) {
                    month = index + 1;
                    break;
                }
            }
            var isoMonth = Pattern.compile(
                    "(?:19|20)\\d{2}[-/](0?[1-9]|1[0-2])")
                    .matcher(value);
            if (isoMonth.find()) {
                String matched = isoMonth.group();
                month = Integer.parseInt(
                        matched.substring(matched.indexOf('-') >= 0
                                ? matched.indexOf('-') + 1
                                : matched.indexOf('/') + 1));
            }
            return year * 12 + month;
        }
    }
}
