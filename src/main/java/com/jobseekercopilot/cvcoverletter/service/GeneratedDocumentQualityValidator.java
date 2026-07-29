package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobseekercopilot.cvcoverletter.dto.ClaimDisposition;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedClaim;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedCv;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedQualification;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.model.ApprovedEvidenceRecord;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import com.jobseekercopilot.cvcoverletter.model.EvidencePurpose;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GeneratedDocumentQualityValidator {
    static final String POLICY_VERSION = "1.0.0";
    private static final int MIN_TARGET_SKILLS = 8;
    private static final int MAX_SKILLS = 12;

    public void validate(
            JsonNode output,
            GeneratedApplicationDocuments documents,
            ClaimEvidenceCatalog catalog
    ) {
        require(output != null && output.isObject(), "$", "structured output is missing");
        require(documents != null
                        && documents.getCv() != null
                        && documents.getCoverLetter() != null,
                "$",
                "generated documents are missing");
        require(catalog != null
                        && catalog.records() != null
                        && !catalog.records().isEmpty(),
                "$",
                "approved evidence catalogue is missing");

        validateCanonicalIdentity(output, catalog.records());
        validateSkills(documents.getCv(), catalog);
        validateDuplicateNarrative(output, "/cv", cvNarrative(output));
        List<TextUnit> coverNarrative = coverLetterNarrative(output);
        validateDuplicateNarrative(output, "/coverLetter", coverNarrative);
        validateQualifications(documents, catalog.records(), coverNarrative);
        validateSelectedEvidenceCoverage(documents, catalog);
        validateStructuredProjects(documents, catalog);
    }

    private void validateCanonicalIdentity(
            JsonNode output,
            List<ApprovedEvidenceRecord> records
    ) {
        String jobTitle = evidenceValue(records, "JOB.TITLE");
        String companyName = evidenceValue(records, "JOB.COMPANY");
        require(StringUtils.hasText(jobTitle), "$.coverLetter.jobTitle",
                "canonical job title evidence is missing");
        require(StringUtils.hasText(companyName), "$.coverLetter.companyName",
                "canonical company evidence is missing");
        require(equalText(output.at("/cv/title").asText(), jobTitle + " CV"),
                "$.cv.title", "title is not canonical");
        require(equalText(output.at("/cv/targetRole").asText(), jobTitle),
                "$.cv.targetRole", "target role is not canonical");
        require(equalText(
                        output.at("/coverLetter/title").asText(),
                        jobTitle + " Cover Letter"),
                "$.coverLetter.title", "title is not canonical");
        require(equalText(output.at("/coverLetter/jobTitle").asText(), jobTitle),
                "$.coverLetter.jobTitle", "job title is not canonical");
        require(equalText(output.at("/coverLetter/companyName").asText(), companyName),
                "$.coverLetter.companyName", "company name is not canonical");
        require(equalText(
                        output.at("/coverLetter/greeting").asText(),
                        "Dear Hiring Manager"),
                "$.coverLetter.greeting", "greeting is not canonical");
        require(equalText(
                        output.at("/coverLetter/signOff").asText(),
                        "Yours faithfully"),
                "$.coverLetter.signOff", "sign-off is not correct for the greeting");
    }

    private void validateSkills(
            GeneratedCv cv,
            ClaimEvidenceCatalog catalog
    ) {
        List<GeneratedCv.CoreSkill> skills = safe(cv.getCoreSkills());
        require(skills.size() <= MAX_SKILLS,
                "$.cv.coreSkills", "contains more than 12 skills");
        Set<String> uniqueNames = new HashSet<>();
        Set<String> uniqueEvidence = new HashSet<>();
        for (int index = 0; index < skills.size(); index++) {
            GeneratedCv.CoreSkill skill = skills.get(index);
            require(skill != null && StringUtils.hasText(skill.getName()),
                    "$.cv.coreSkills[" + index + "]", "skill name is missing");
            require(uniqueNames.add(normalise(skill.getName())),
                    "$.cv.coreSkills[" + index + "].name",
                    "duplicate normalised skill");
            if (StringUtils.hasText(skill.getEvidence())) {
                require(uniqueEvidence.add(normalise(skill.getEvidence())),
                        "$.cv.coreSkills[" + index + "].evidence",
                        "repeated skill evidence");
            }
        }

        if (!"2.0".equals(catalog.catalogVersion())) {
            return;
        }
        long availableSkills = catalog.records().stream()
                .filter(record ->
                        record.source() == EvidenceSource.EVIDENCE_SNAPSHOT)
                .filter(record -> record.purpose().supports(EvidencePurpose.CV))
                .filter(record -> "DEMONSTRATED_SKILL".equals(record.factType()))
                .map(ApprovedEvidenceRecord::value)
                .map(this::normalise)
                .distinct()
                .count();
        int minimum = (int) Math.min(MIN_TARGET_SKILLS, availableSkills);
        require(skills.size() >= minimum,
                "$.cv.coreSkills",
                "does not cover the available confirmed skills");
    }

    private void validateDuplicateNarrative(
            JsonNode output,
            String documentPath,
            List<TextUnit> units
    ) {
        require(output.at(documentPath).isObject(),
                documentPath, "document is malformed");
        Map<String, String> firstPathByText = new HashMap<>();
        for (TextUnit unit : units) {
            String normalized = normalise(unit.text());
            if (normalized.isEmpty()) {
                continue;
            }
            String firstPath = firstPathByText.putIfAbsent(
                    normalized,
                    unit.path());
            require(firstPath == null,
                    unit.path(),
                    "duplicate normalised line or paragraph");
        }
    }

    private List<TextUnit> cvNarrative(JsonNode output) {
        List<TextUnit> units = new ArrayList<>();
        addText(units, output, "/cv/personalSummary");
        addObjectField(units, output, "/cv/coreSkills", "evidence");
        addObjectField(units, output, "/cv/projects", "description");
        addNestedTextArray(units, output, "/cv/projects", "highlights");
        addObjectField(units, output, "/cv/workHistory", "tailoredDescription");
        addNestedTextArray(units, output, "/cv/workHistory", "responsibilities");
        return List.copyOf(units);
    }

    private List<TextUnit> coverLetterNarrative(JsonNode output) {
        List<TextUnit> units = new ArrayList<>();
        addText(units, output, "/coverLetter/openingParagraph");
        addTextArray(units, output, "/coverLetter/bodyParagraphs");
        addText(units, output, "/coverLetter/closingParagraph");
        return List.copyOf(units);
    }

    private void validateQualifications(
            GeneratedApplicationDocuments documents,
            List<ApprovedEvidenceRecord> records,
            List<TextUnit> coverNarrative
    ) {
        Set<String> qualificationKeys = new HashSet<>();
        List<GeneratedQualification> qualifications =
                safe(documents.getCv().getQualifications());
        for (int index = 0; index < qualifications.size(); index++) {
            GeneratedQualification qualification = qualifications.get(index);
            require(qualification != null
                            && StringUtils.hasText(
                                    qualification.getQualificationName()),
                    "$.cv.qualifications[" + index + "]",
                    "qualification name is missing");
            String date = StringUtils.hasText(qualification.getDateAchieved())
                    ? qualification.getDateAchieved()
                    : qualification.getExpectedCompletion();
            String key = normalise(
                    qualification.getQualificationName()
                            + "|"
                            + qualification.getIssuingBody()
                            + "|"
                            + date);
            require(qualificationKeys.add(key),
                    "$.cv.qualifications[" + index + "]",
                    "qualification is repeated");
        }

        Set<String> qualificationFacts = records.stream()
                .filter(record ->
                        record.purpose().supports(EvidencePurpose.COVER_LETTER))
                .filter(record -> "EDUCATION".equals(record.category())
                        || "QUALIFICATION_TRAINING".equals(record.category()))
                .filter(record -> "HEADING".equals(record.factType())
                        || "PROGRAMME_OR_SUBJECT".equals(record.factType())
                        || "QUALIFICATION_TITLE".equals(record.factType()))
                .map(ApprovedEvidenceRecord::value)
                .filter(StringUtils::hasText)
                .map(this::normalise)
                .filter(this::substantiveQualificationPhrase)
                .collect(java.util.stream.Collectors.toSet());
        for (String fact : qualificationFacts) {
            long occurrences = coverNarrative.stream()
                    .map(TextUnit::text)
                    .filter(text -> containsBoundedPhrase(text, fact))
                    .count();
            require(occurrences <= 1,
                    "$.coverLetter",
                    "qualification evidence is repeated");
        }
    }

    private boolean substantiveQualificationPhrase(String phrase) {
        return phrase.length() >= 12
                && phrase.split("\\s+").length >= 2;
    }

    private boolean containsBoundedPhrase(String text, String phrase) {
        String normalized = normalise(text);
        return Pattern.compile(
                        "(?<![\\p{L}\\p{N}])"
                                + Pattern.quote(phrase)
                                + "(?![\\p{L}\\p{N}])")
                .matcher(normalized)
                .find();
    }

    private void validateSelectedEvidenceCoverage(
            GeneratedApplicationDocuments documents,
            ClaimEvidenceCatalog catalog
    ) {
        if (!"2.0".equals(catalog.catalogVersion())) {
            return;
        }
        Map<SelectionKey, List<ApprovedEvidenceRecord>> selections =
                snapshotSelections(catalog.records());
        Map<String, Set<String>> finalPathsByEvidenceId =
                finalPathsByEvidenceId(documents.getClaims());
        for (Map.Entry<SelectionKey, List<ApprovedEvidenceRecord>> entry :
                selections.entrySet()) {
            Set<String> selectedPaths = new LinkedHashSet<>();
            for (ApprovedEvidenceRecord record : entry.getValue()) {
                selectedPaths.addAll(finalPathsByEvidenceId.getOrDefault(
                        record.evidenceId(),
                        Set.of()));
            }
            require(!selectedPaths.isEmpty(),
                    "$.claims",
                    "a selected evidence entry is missing from final content");
            String requiredPrefix = requiredSectionPrefix(entry.getKey());
            if (requiredPrefix != null) {
                require(selectedPaths.stream()
                                .anyMatch(path ->
                                        path.startsWith(requiredPrefix)),
                        "$.claims",
                        "selected evidence is not represented in its governed section");
            }
        }
    }

    private void validateStructuredProjects(
            GeneratedApplicationDocuments documents,
            ClaimEvidenceCatalog catalog
    ) {
        if (!"2.0".equals(catalog.catalogVersion())) {
            return;
        }
        Map<String, ApprovedEvidenceRecord> evidenceById = new HashMap<>();
        catalog.records().forEach(record ->
                evidenceById.put(record.evidenceId(), record));
        List<GeneratedClaim> claims = safe(documents.getClaims());
        int projectCount = safe(documents.getCv().getProjects()).size();
        for (int index = 0; index < projectCount; index++) {
            require(StringUtils.hasText(
                            documents.getCv().getProjects().get(index)
                                    .getTitle()),
                    "$.cv.projects[" + index + "].title",
                    "project title is missing");
            String prefix = "/cv/projects/" + index + "/";
            Set<String> selections = new LinkedHashSet<>();
            Set<String> categories = new LinkedHashSet<>();
            for (GeneratedClaim claim : claims) {
                if (!isFinal(claim)) {
                    continue;
                }
                boolean projectClaim = safe(claim.getContentPaths()).stream()
                        .anyMatch(path -> path.startsWith(prefix));
                if (!projectClaim) {
                    continue;
                }
                for (String evidenceId : safe(claim.getEvidenceIds())) {
                    ApprovedEvidenceRecord record =
                            evidenceById.get(evidenceId);
                    if (record == null
                            || record.source()
                                    != EvidenceSource.EVIDENCE_SNAPSHOT) {
                        continue;
                    }
                    selections.add(selectionPath(record.sourcePath()));
                    categories.add(record.category());
                }
            }
            require(selections.size() == 1
                            && categories.equals(Set.of("PROJECT")),
                    "$.cv.projects[" + index + "]",
                    "project fields must come from one selected project entry");
        }
    }

    private Map<SelectionKey, List<ApprovedEvidenceRecord>> snapshotSelections(
            List<ApprovedEvidenceRecord> records
    ) {
        Map<SelectionKey, List<ApprovedEvidenceRecord>> selections =
                new LinkedHashMap<>();
        for (ApprovedEvidenceRecord record : records) {
            if (record.source() != EvidenceSource.EVIDENCE_SNAPSHOT) {
                continue;
            }
            SelectionKey key = new SelectionKey(
                    record.purpose(),
                    selectionPath(record.sourcePath()),
                    record.category());
            selections.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(record);
        }
        return selections;
    }

    private Map<String, Set<String>> finalPathsByEvidenceId(
            List<GeneratedClaim> claims
    ) {
        Map<String, Set<String>> pathsByEvidenceId = new HashMap<>();
        for (GeneratedClaim claim : safe(claims)) {
            if (!isFinal(claim)) {
                continue;
            }
            for (String evidenceId : safe(claim.getEvidenceIds())) {
                pathsByEvidenceId
                        .computeIfAbsent(
                                evidenceId,
                                ignored -> new LinkedHashSet<>())
                        .addAll(safe(claim.getContentPaths()));
            }
        }
        return pathsByEvidenceId;
    }

    private String requiredSectionPrefix(SelectionKey key) {
        if (key.purpose() != EvidencePurpose.CV || key.category() == null) {
            return null;
        }
        return switch (key.category()) {
            case "PROJECT" -> "/cv/projects/";
            case "EMPLOYMENT" -> "/cv/workHistory/";
            case "EDUCATION", "QUALIFICATION_TRAINING" ->
                    "/cv/qualifications/";
            default -> null;
        };
    }

    private boolean isFinal(GeneratedClaim claim) {
        return claim != null
                && (claim.getDisposition() == ClaimDisposition.SUPPORTED
                        || claim.getDisposition()
                                == ClaimDisposition.REWORDED);
    }

    private String evidenceValue(
            List<ApprovedEvidenceRecord> records,
            String evidenceId
    ) {
        return records.stream()
                .filter(record -> evidenceId.equals(record.evidenceId()))
                .map(ApprovedEvidenceRecord::value)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("");
    }

    private String selectionPath(String sourcePath) {
        if (!StringUtils.hasText(sourcePath)) {
            return "";
        }
        int facts = sourcePath.indexOf("/facts/");
        return facts < 0 ? sourcePath : sourcePath.substring(0, facts);
    }

    private void addObjectField(
            List<TextUnit> units,
            JsonNode output,
            String arrayPath,
            String field
    ) {
        JsonNode array = output.at(arrayPath);
        for (int index = 0; index < array.size(); index++) {
            addText(units, output, arrayPath + "/" + index + "/" + field);
        }
    }

    private void addNestedTextArray(
            List<TextUnit> units,
            JsonNode output,
            String arrayPath,
            String field
    ) {
        JsonNode array = output.at(arrayPath);
        for (int index = 0; index < array.size(); index++) {
            addTextArray(units, output, arrayPath + "/" + index + "/" + field);
        }
    }

    private void addTextArray(
            List<TextUnit> units,
            JsonNode output,
            String arrayPath
    ) {
        JsonNode array = output.at(arrayPath);
        for (int index = 0; index < array.size(); index++) {
            addText(units, output, arrayPath + "/" + index);
        }
    }

    private void addText(
            List<TextUnit> units,
            JsonNode output,
            String path
    ) {
        JsonNode value = output.at(path);
        if (value.isTextual() && StringUtils.hasText(value.textValue())) {
            units.add(new TextUnit(path, value.textValue()));
        }
    }

    private boolean equalText(String left, String right) {
        return normalise(left).equals(normalise(right));
    }

    private String normalise(String value) {
        return value == null
                ? ""
                : Normalizer.normalize(value, Normalizer.Form.NFKC)
                        .trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceFirst("^[-•\\s]+", "")
                        .replaceAll("\\s+", " ");
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private void require(boolean condition, String path, String reason) {
        if (!condition) {
            throw new InvalidLlmResponseException(
                    "LLM response failed deterministic quality validation at "
                            + path
                            + ": "
                            + reason);
        }
    }

    private record TextUnit(String path, String text) {
    }

    private record SelectionKey(
            EvidencePurpose purpose,
            String sourcePath,
            String category
    ) {
    }
}
