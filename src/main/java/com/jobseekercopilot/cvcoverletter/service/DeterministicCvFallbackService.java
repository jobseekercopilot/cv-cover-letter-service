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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds a conservative CV from already approved evidence. It deliberately
 * performs no semantic embellishment: canonical fields and narrative bullets
 * are copied from evidence, while the only generated prose is a fixed
 * application-intent summary backed by the requested job title.
 */
@Component
public class DeterministicCvFallbackService {
    static final String FALLBACK_VERSION = "deterministic-evidence-cv-1.0.0";
    private static final int MAX_SKILLS = 12;
    private static final int MAX_EMPLOYMENT = 8;
    private static final int MAX_QUALIFICATIONS = 8;
    private static final int MAX_PROJECTS = 4;

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

        String summary = "Application for the "
                + input.job().title()
                + " role, supported by the verified skills and experience detailed below.";
        cv.put("personalSummary", summary);
        ObjectNode summaryClaim = root.putObject("personalSummaryClaim");
        summaryClaim.put("claimId", "CLAIM-9003");
        summaryClaim.put("disposition", "REWORDED");
        summaryClaim.putArray("evidenceIds")
                .add(evidence.requiredId("REQUEST.GENERATION_INTENT"))
                .add(evidence.requiredId("JOB.TITLE"))
                .add(evidence.firstClaimantEvidenceId());
        summaryClaim.put("contentPath", "/cv/personalSummary");
        summaryClaim.put("reviewText", "");

        addSkills(cv, input);
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
            NormalizedGenerationInput input) {
        ArrayNode skills = cv.putArray("coreSkills");
        input.profile().skills().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .limit(MAX_SKILLS)
                .forEach(skill -> {
                    ObjectNode item = skills.addObject();
                    item.put("name", skill);
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
        for (EvidenceGroup group : evidence.groups(record ->
                record.purpose() == EvidencePurpose.CV
                        && List.of("EMPLOYMENT", "FREELANCE")
                                .contains(record.category()))) {
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
            group.records().stream()
                    .filter(record -> List.of(
                            "RESPONSIBILITIES", "ACHIEVEMENTS", "DESCRIPTION")
                            .contains(record.factType()))
                    .limit(4)
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
            item.put("status", "");
            projectField(item, "grade", group.first(
                    "RESULT", "RESULT_OR_STATUS"), claims,
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
        ObjectNode item = target.addObject();
        item.put("text", text.trim());
        item.put("disposition", "SUPPORTED");
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
    }
}
