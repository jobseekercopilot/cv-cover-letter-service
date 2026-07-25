package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobseekercopilot.cvcoverletter.dto.ClaimDisposition;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedClaim;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.model.ApprovedEvidenceRecord;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ClaimEvidenceValidator {
    static final String POLICY_VERSION = "1.0.0";

    private static final Pattern NUMERIC_CLAIM =
            Pattern.compile("(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?%?(?![\\p{L}\\p{N}])");
    private static final Pattern SENSITIVE_CLAIM = Pattern.compile(
            "(?i)\\b(?:right to work|work authori[sz]ation|visa|sponsorship"
                    + "|available (?:immediately|from)|notice period"
                    + "|salary|compensation|£\\s*\\d+|\\$\\s*\\d+"
                    + "|passionate|enthusiastic|excited|motivated|keen"
                    + "|phd|doctorate|mba|master'?s degree"
                    + "|aws|amazon web services|azure|gcp|google cloud"
                    + "|docker|kubernetes|terraform|react|angular|python|java|spring)\\b");

    public void validate(
            JsonNode output,
            GeneratedApplicationDocuments documents,
            ClaimEvidenceCatalog catalog
    ) {
        if (catalog == null || catalog.records() == null || catalog.records().isEmpty()) {
            throw new IllegalStateException("Approved claim evidence catalogue is missing.");
        }
        List<GeneratedClaim> claims = documents.getClaims();
        if (claims == null) {
            throw invalid("$.claims", "claim ledger is missing");
        }

        Map<String, ApprovedEvidenceRecord> evidenceById = new HashMap<>();
        for (ApprovedEvidenceRecord record : catalog.records()) {
            if (evidenceById.put(record.evidenceId(), record) != null) {
                throw new IllegalStateException("Approved evidence catalogue contains duplicate IDs.");
            }
        }

        Set<String> expectedPaths = claimBearingPaths(output);
        Set<String> coveredPaths = new HashSet<>();
        Set<String> claimIds = new HashSet<>();
        for (int index = 0; index < claims.size(); index++) {
            validateClaim(
                    output,
                    claims.get(index),
                    index,
                    expectedPaths,
                    coveredPaths,
                    claimIds,
                    evidenceById);
        }
        if (!coveredPaths.equals(expectedPaths)) {
            throw invalid("$.claims", "final content contains an unaccounted claim path");
        }
    }

    private void validateClaim(
            JsonNode output,
            GeneratedClaim claim,
            int index,
            Set<String> expectedPaths,
            Set<String> coveredPaths,
            Set<String> claimIds,
            Map<String, ApprovedEvidenceRecord> evidenceById
    ) {
        String claimPath = "$.claims[" + index + "]";
        require(claim != null, claimPath, "claim is missing");
        require(StringUtils.hasText(claim.getClaimId()), claimPath + ".claimId", "claim ID is blank");
        require(claimIds.add(claim.getClaimId()), claimPath + ".claimId", "claim ID is duplicated");
        require(claim.getDisposition() != null, claimPath + ".disposition", "disposition is missing");

        List<String> evidenceIds = safe(claim.getEvidenceIds());
        List<String> contentPaths = safe(claim.getContentPaths());
        List<ApprovedEvidenceRecord> evidence = new ArrayList<>();
        Set<String> uniqueEvidenceIds = new HashSet<>();
        for (String evidenceId : evidenceIds) {
            require(StringUtils.hasText(evidenceId), claimPath + ".evidenceIds", "evidence ID is blank");
            require(uniqueEvidenceIds.add(evidenceId),
                    claimPath + ".evidenceIds", "evidence ID is duplicated");
            ApprovedEvidenceRecord record = evidenceById.get(evidenceId);
            require(record != null, claimPath + ".evidenceIds", "evidence ID is not approved");
            evidence.add(record);
        }

        boolean finalContent = claim.getDisposition() == ClaimDisposition.SUPPORTED
                || claim.getDisposition() == ClaimDisposition.REWORDED;
        if (!finalContent) {
            require(contentPaths.isEmpty(),
                    claimPath + ".contentPaths", "review-only claim points at final content");
            require(StringUtils.hasText(claim.getReviewText()),
                    claimPath + ".reviewText", "review-only claim has no review text");
            return;
        }

        require(!evidence.isEmpty(), claimPath + ".evidenceIds", "final claim has no approved evidence");
        require(!contentPaths.isEmpty(), claimPath + ".contentPaths", "final claim has no content path");
        require(!StringUtils.hasText(claim.getReviewText()),
                claimPath + ".reviewText", "final claim contains review-only text");
        for (String contentPath : contentPaths) {
            require(expectedPaths.contains(contentPath),
                    claimPath + ".contentPaths", "content path is not an approved final claim path");
            require(coveredPaths.add(contentPath),
                    claimPath + ".contentPaths", "content path is covered more than once");
            JsonNode value = output.at(contentPath);
            require(value.isTextual() && StringUtils.hasText(value.textValue()),
                    claimPath + ".contentPaths", "content path does not contain final text");
            validateEvidenceAlignment(contentPath, value.textValue(), evidence, claimPath);
        }
    }

    private void validateEvidenceAlignment(
            String contentPath,
            String content,
            List<ApprovedEvidenceRecord> evidence,
            String claimPath
    ) {
        Predicate<ApprovedEvidenceRecord> atomicEvidence = atomicEvidenceFor(contentPath);
        if (atomicEvidence != null) {
            require(evidence.stream()
                            .filter(atomicEvidence)
                            .anyMatch(record -> equalText(content, record.value())),
                    claimPath + ".evidenceIds",
                    "atomic final claim is not an exact approved fact");
        } else {
            require(evidence.stream().anyMatch(record -> record.source() != EvidenceSource.REQUEST),
                    claimPath + ".evidenceIds",
                    "final claim is supported only by generation intent");
            if (contentPath.startsWith("/cv/")) {
                require(evidence.stream().anyMatch(
                                record -> record.source() == EvidenceSource.PROFILE),
                        claimPath + ".evidenceIds",
                        "candidate claim has no approved profile evidence");
            }
        }

        String evidenceText = evidence.stream()
                .map(ApprovedEvidenceRecord::value)
                .map(this::normalise)
                .reduce("", (left, right) -> left + " " + right);
        requireMatchesAreSupported(
                NUMERIC_CLAIM.matcher(content),
                evidenceText,
                claimPath,
                "numeric claim is absent from approved evidence");
        requireMatchesAreSupported(
                SENSITIVE_CLAIM.matcher(content),
                evidenceText,
                claimPath,
                "sensitive or specific claim is absent from approved evidence");
    }

    private Predicate<ApprovedEvidenceRecord> atomicEvidenceFor(String path) {
        if (path.equals("/cv/targetRole")) {
            return record -> record.evidenceId().startsWith("PROFILE.TARGET_ROLE.")
                    || record.evidenceId().equals("JOB.TITLE");
        }
        if (path.matches("/cv/coreSkills/\\d+/name")) {
            return record -> record.evidenceId().startsWith("PROFILE.SKILL.");
        }
        if (path.matches("/cv/qualifications/\\d+/qualificationName")) {
            return suffix(".NAME");
        }
        if (path.matches("/cv/qualifications/\\d+/issuingBody")) {
            return suffix(".ISSUING_BODY");
        }
        if (path.matches("/cv/qualifications/\\d+/status")) {
            return suffix(".STATUS");
        }
        if (path.matches("/cv/qualifications/\\d+/grade")) {
            return suffix(".GRADE");
        }
        if (path.matches("/cv/qualifications/\\d+/dateAchieved")) {
            return suffix(".DATE_ACHIEVED");
        }
        if (path.matches("/cv/qualifications/\\d+/expectedCompletion")) {
            return suffix(".EXPECTED_COMPLETION");
        }
        if (path.matches("/cv/workHistory/\\d+/jobTitle")) {
            return employmentSuffix(".JOB_TITLE");
        }
        if (path.matches("/cv/workHistory/\\d+/employer")) {
            return employmentSuffix(".EMPLOYER");
        }
        if (path.matches("/cv/workHistory/\\d+/startDate")) {
            return employmentSuffix(".START_DATE");
        }
        if (path.matches("/cv/workHistory/\\d+/endDate")) {
            return employmentSuffix(".END_DATE");
        }
        if (path.equals("/coverLetter/jobTitle")) {
            return record -> record.evidenceId().equals("JOB.TITLE");
        }
        if (path.equals("/coverLetter/companyName")) {
            return record -> record.evidenceId().equals("JOB.COMPANY");
        }
        return null;
    }

    private Predicate<ApprovedEvidenceRecord> suffix(String suffix) {
        return record -> record.evidenceId().startsWith("PROFILE.QUALIFICATION.")
                && record.evidenceId().endsWith(suffix);
    }

    private Predicate<ApprovedEvidenceRecord> employmentSuffix(String suffix) {
        return record -> record.evidenceId().startsWith("PROFILE.EMPLOYMENT.")
                && record.evidenceId().endsWith(suffix);
    }

    private void requireMatchesAreSupported(
            Matcher matcher,
            String evidenceText,
            String claimPath,
            String reason
    ) {
        while (matcher.find()) {
            String matched = normalise(matcher.group());
            Pattern supported = Pattern.compile(
                    "(?<![\\p{L}\\p{N}])"
                            + Pattern.quote(matched)
                            + "(?![\\p{L}\\p{N}])");
            require(supported.matcher(evidenceText).find(),
                    claimPath + ".evidenceIds", reason);
        }
    }

    private Set<String> claimBearingPaths(JsonNode output) {
        Set<String> paths = new LinkedHashSet<>();
        addText(paths, output, "/cv/title");
        addText(paths, output, "/cv/targetRole");
        addText(paths, output, "/cv/personalSummary");
        addObjectArrayText(paths, output, "/cv/coreSkills", List.of("name", "evidence"));
        addObjectArrayText(paths, output, "/cv/qualifications", List.of(
                "qualificationName",
                "issuingBody",
                "status",
                "grade",
                "dateAchieved",
                "expectedCompletion"));
        addObjectArrayText(paths, output, "/cv/workHistory", List.of(
                "jobTitle", "employer", "startDate", "endDate", "tailoredDescription"));
        addNestedTextArray(paths, output, "/cv/workHistory", "responsibilities");
        addText(paths, output, "/coverLetter/title");
        addText(paths, output, "/coverLetter/jobTitle");
        addText(paths, output, "/coverLetter/companyName");
        addText(paths, output, "/coverLetter/openingParagraph");
        addTextArray(paths, output, "/coverLetter/bodyParagraphs");
        addText(paths, output, "/coverLetter/closingParagraph");
        return paths;
    }

    private void addObjectArrayText(
            Set<String> paths,
            JsonNode output,
            String arrayPath,
            List<String> fields
    ) {
        JsonNode array = output.at(arrayPath);
        for (int index = 0; index < array.size(); index++) {
            for (String field : fields) {
                addText(paths, output, arrayPath + "/" + index + "/" + field);
            }
        }
    }

    private void addNestedTextArray(
            Set<String> paths,
            JsonNode output,
            String arrayPath,
            String field
    ) {
        JsonNode array = output.at(arrayPath);
        for (int index = 0; index < array.size(); index++) {
            addTextArray(paths, output, arrayPath + "/" + index + "/" + field);
        }
    }

    private void addTextArray(Set<String> paths, JsonNode output, String arrayPath) {
        JsonNode array = output.at(arrayPath);
        for (int index = 0; index < array.size(); index++) {
            addText(paths, output, arrayPath + "/" + index);
        }
    }

    private void addText(Set<String> paths, JsonNode output, String path) {
        JsonNode value = output.at(path);
        if (value.isTextual() && StringUtils.hasText(value.textValue())) {
            paths.add(path);
        }
    }

    private List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean equalText(String left, String right) {
        return normalise(left).equals(normalise(right));
    }

    private String normalise(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private void require(boolean condition, String path, String reason) {
        if (!condition) {
            throw invalid(path, reason);
        }
    }

    private InvalidLlmResponseException invalid(String path, String reason) {
        return new InvalidLlmResponseException(
                "LLM response failed claim evidence validation at " + path + ": " + reason);
    }
}
