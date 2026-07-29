package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobseekercopilot.cvcoverletter.dto.ClaimDisposition;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedClaim;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.model.ApprovedEvidenceRecord;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
import com.jobseekercopilot.cvcoverletter.model.EvidencePurpose;
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
    static final String POLICY_VERSION = "2.1.0";
    private static final int MAX_CLAIMS = 40;
    private static final int MAX_CLAIM_REFERENCES = 30;
    private static final int MAX_REVIEW_TEXT_LENGTH = 500;

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
        if (documents.getClaims() == null) {
            throw invalid("$.claims", "claim ledger is missing");
        }
        Map<String, List<ApprovedEvidenceRecord>> evidenceById =
                new HashMap<>();
        Set<String> evidenceScopeKeys = new HashSet<>();
        for (ApprovedEvidenceRecord record : catalog.records()) {
            if (record == null
                    || !StringUtils.hasText(record.evidenceId())
                    || record.purpose() == null
                    || !evidenceScopeKeys.add(
                            record.evidenceId()
                                    + ":"
                                    + record.purpose())) {
                throw new IllegalStateException(
                        "Approved evidence catalogue contains invalid or duplicate scoped IDs.");
            }
            if ("2.0".equals(catalog.catalogVersion())) {
                if (record.source() == EvidenceSource.PROFILE
                        || record.evidenceId().startsWith("PROFILE.")) {
                    throw new IllegalStateException(
                            "Versioned evidence catalogue contains positional profile evidence.");
                }
                if (record.source()
                        == EvidenceSource.EVIDENCE_SNAPSHOT) {
                    try {
                        java.util.UUID.fromString(record.evidenceId());
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalStateException(
                                "Versioned evidence catalogue contains a non-stable fact ID.");
                    }
                }
            }
            evidenceById
                    .computeIfAbsent(
                            record.evidenceId(),
                            ignored -> new ArrayList<>())
                    .add(record);
        }
        normalizeCoreSkillEvidence(output, catalog.records());
        pruneUnsupportedWorkHistory(output, evidenceById);
        Set<String> expectedPaths = claimBearingPaths(output);
        List<GeneratedClaim> claims = normalizeDuplicateClaimIds(
                normalizeDuplicateCoverage(
                        splitPurposeSpanningClaims(
                                expandContainerContentPaths(
                                        documents.getClaims(),
                                        expectedPaths),
                                evidenceById)));
        applyExactFallbackContent(
                output,
                claims,
                catalog.records(),
                expectedPaths);
        claims = addExactCoverageClaims(
                output,
                claims,
                evidenceById,
                expectedPaths);
        claims = enrichAtomicEvidenceReferences(
                output,
                claims,
                evidenceById);
        claims = normalizeDuplicateClaimIds(
                splitOversizedContentPathClaims(claims));
        documents.setClaims(claims);

        require(claims.size() <= MAX_CLAIMS,
                "$.claims",
                "normalized claim ledger exceeds the bounded claim count");
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
                    evidenceById,
                    "2.0".equals(catalog.catalogVersion()));
        }
        if (!coveredPaths.equals(expectedPaths)) {
            Set<String> unaccounted = new LinkedHashSet<>(expectedPaths);
            unaccounted.removeAll(coveredPaths);
            throw invalid(
                    "$.claims",
                    "final content contains unaccounted claim paths "
                            + unaccounted);
        }
    }

    private void pruneUnsupportedWorkHistory(
            JsonNode output,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        JsonNode history = output.at("/cv/workHistory");
        if (!(history instanceof ArrayNode historyArray)) {
            return;
        }
        for (int index = historyArray.size() - 1; index >= 0; index--) {
            int currentIndex = index;
            JsonNode entry = historyArray.get(index);
            boolean supported = List.of(
                            "jobTitle",
                            "employer",
                            "startDate")
                    .stream()
                    .allMatch(field -> hasExactAtomicEvidence(
                            entry.path(field).asText(),
                            "/cv/workHistory/" + currentIndex + "/" + field,
                            EvidencePurpose.CV,
                            evidenceById));
            if (!supported) {
                historyArray.remove(index);
            }
        }
    }

    private boolean hasExactAtomicEvidence(
            String value,
            String contentPath,
            EvidencePurpose purpose,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        Predicate<ApprovedEvidenceRecord> atomicEvidence =
                atomicEvidenceFor(contentPath);
        return StringUtils.hasText(value)
                && atomicEvidence != null
                && evidenceById.values().stream()
                        .flatMap(List::stream)
                        .anyMatch(record ->
                                record.purpose().supports(purpose)
                                        && candidateEvidence(record.source())
                                        && atomicEvidence.test(record)
                                        && equalText(value, record.value()));
    }

    private void applyExactFallbackContent(
            JsonNode output,
            List<GeneratedClaim> claims,
            List<ApprovedEvidenceRecord> records,
            Set<String> expectedPaths
    ) {
        Set<String> covered = claims.stream()
                .filter(java.util.Objects::nonNull)
                .filter(claim -> isFinalContent(claim.getDisposition()))
                .flatMap(claim -> safe(claim.getContentPaths()).stream())
                .collect(java.util.stream.Collectors.toSet());
        for (String contentPath : expectedPaths) {
            if (covered.contains(contentPath)) {
                continue;
            }
            EvidencePurpose purpose = contentPath.startsWith("/cv/")
                    ? EvidencePurpose.CV
                    : EvidencePurpose.COVER_LETTER;
            ApprovedEvidenceRecord fallback = fallbackEvidence(
                    contentPath,
                    purpose,
                    records);
            if (fallback != null) {
                replaceText(output, contentPath, fallback.value());
            }
        }
    }

    private ApprovedEvidenceRecord fallbackEvidence(
            String contentPath,
            EvidencePurpose purpose,
            List<ApprovedEvidenceRecord> records
    ) {
        if (contentPath.equals("/cv/title")
                || contentPath.equals("/coverLetter/title")) {
            return records.stream()
                    .filter(record -> record.evidenceId().equals("JOB.TITLE"))
                    .findFirst()
                    .orElse(null);
        }
        if (!requiresConfirmedCandidateEvidence(contentPath)) {
            return null;
        }
        return records.stream()
                .filter(record -> record.purpose().supports(purpose))
                .filter(record -> candidateEvidence(record.source()))
                .filter(record -> record.factType() == null
                        || factType(
                                record,
                                "DESCRIPTION",
                                "RESPONSIBILITIES",
                                "ACHIEVEMENT",
                                "HEADING",
                                "DEMONSTRATED_SKILL"))
                .findFirst()
                .orElse(null);
    }

    private void replaceText(
            JsonNode output,
            String contentPath,
            String value
    ) {
        int separator = contentPath.lastIndexOf('/');
        JsonNode parent = output.at(contentPath.substring(0, separator));
        String field = contentPath.substring(separator + 1);
        if (parent instanceof ObjectNode objectNode) {
            objectNode.put(field, value);
        } else if (parent instanceof ArrayNode arrayNode) {
            arrayNode.set(Integer.parseInt(field),
                    arrayNode.textNode(value));
        }
    }

    private List<GeneratedClaim> normalizeDuplicateCoverage(
            List<GeneratedClaim> claims
    ) {
        List<GeneratedClaim> normalized = new ArrayList<>(claims.size());
        Map<String, GeneratedClaim> ownerByPath = new HashMap<>();
        for (GeneratedClaim claim : claims) {
            if (claim == null || !isFinalContent(claim.getDisposition())) {
                normalized.add(claim);
                continue;
            }
            List<String> unclaimedPaths = new ArrayList<>();
            for (String contentPath : safe(claim.getContentPaths())) {
                GeneratedClaim owner = ownerByPath.get(contentPath);
                if (owner == null) {
                    unclaimedPaths.add(contentPath);
                    continue;
                }
                LinkedHashSet<String> mergedEvidence =
                        new LinkedHashSet<>(safe(owner.getEvidenceIds()));
                mergedEvidence.addAll(safe(claim.getEvidenceIds()));
                owner.setEvidenceIds(List.copyOf(mergedEvidence));
            }
            if (unclaimedPaths.isEmpty()) {
                continue;
            }
            GeneratedClaim copy = copyWithClaimId(
                    claim,
                    claim.getClaimId());
            copy.setContentPaths(List.copyOf(unclaimedPaths));
            normalized.add(copy);
            for (String contentPath : unclaimedPaths) {
                ownerByPath.put(contentPath, copy);
            }
        }
        return List.copyOf(normalized);
    }

    private List<GeneratedClaim> addExactCoverageClaims(
            JsonNode output,
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            Set<String> expectedPaths
    ) {
        Set<String> covered = claims.stream()
                .filter(java.util.Objects::nonNull)
                .filter(claim -> isFinalContent(claim.getDisposition()))
                .flatMap(claim -> safe(claim.getContentPaths()).stream())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> usedClaimIds = claims.stream()
                .filter(java.util.Objects::nonNull)
                .map(GeneratedClaim::getClaimId)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());
        List<GeneratedClaim> normalized = new ArrayList<>(claims);
        int nextGeneratedClaimNumber = 2000;
        for (String contentPath : expectedPaths) {
            if (covered.contains(contentPath)) {
                continue;
            }
            JsonNode value = output.at(contentPath);
            EvidencePurpose purpose = contentPath.startsWith("/cv/")
                    ? EvidencePurpose.CV
                    : EvidencePurpose.COVER_LETTER;
            Predicate<ApprovedEvidenceRecord> atomicEvidence =
                    atomicEvidenceFor(contentPath);
            List<String> matchingEvidenceIds = evidenceById.entrySet().stream()
                    .filter(entry -> entry.getValue().stream()
                            .anyMatch(record ->
                                    record.purpose().supports(purpose)
                                            && (!requiresConfirmedCandidateEvidence(
                                                    contentPath)
                                                    || candidateEvidence(
                                                            record.source()))
                                            && (atomicEvidence == null
                                                    || atomicEvidence.test(
                                                            record))
                                            && equalText(
                                                    value.asText(),
                                                    record.value())))
                    .map(Map.Entry::getKey)
                    .toList();
            if (matchingEvidenceIds.isEmpty()) {
                continue;
            }
            String generatedClaimId;
            do {
                generatedClaimId = "CLAIM-" + nextGeneratedClaimNumber++;
            } while (usedClaimIds.contains(generatedClaimId));
            usedClaimIds.add(generatedClaimId);
            GeneratedClaim generated = new GeneratedClaim();
            generated.setClaimId(generatedClaimId);
            generated.setDisposition(ClaimDisposition.SUPPORTED);
            generated.setEvidenceIds(matchingEvidenceIds);
            generated.setContentPaths(List.of(contentPath));
            generated.setReviewText("");
            normalized.add(generated);
        }
        return List.copyOf(normalized);
    }

    private void normalizeCoreSkillEvidence(
            JsonNode output,
            List<ApprovedEvidenceRecord> records
    ) {
        JsonNode skills = output.at("/cv/coreSkills");
        for (int index = 0; index < skills.size(); index++) {
            JsonNode skill = skills.get(index);
            if (!(skill instanceof ObjectNode skillObject)) {
                continue;
            }
            String skillName = skill.path("name").asText();
            ApprovedEvidenceRecord skillEvidence = records.stream()
                    .filter(record -> record.purpose().supports(EvidencePurpose.CV))
                    .filter(record -> candidateEvidence(record.source()))
                    .filter(record -> factType(record, "DEMONSTRATED_SKILL")
                            || record.evidenceId().startsWith("PROFILE.SKILL."))
                    .filter(record -> equalText(skillName, record.value()))
                    .findFirst()
                    .orElse(null);
            if (skillEvidence == null) {
                continue;
            }
            String selectionPath = evidenceSelectionPath(
                    skillEvidence.sourcePath());
            ApprovedEvidenceRecord explanatoryEvidence = records.stream()
                    .filter(record -> record.purpose().supports(EvidencePurpose.CV))
                    .filter(record -> candidateEvidence(record.source()))
                    .filter(record -> selectionPath != null
                            && selectionPath.equals(
                                    evidenceSelectionPath(
                                            record.sourcePath())))
                    .filter(record -> factType(
                            record,
                            "ACHIEVEMENT",
                            "DESCRIPTION",
                            "RESPONSIBILITIES",
                            "HEADING"))
                    .findFirst()
                    .orElse(skillEvidence);
            skillObject.put("evidence", explanatoryEvidence.value());
        }
    }

    private String evidenceSelectionPath(String sourcePath) {
        if (sourcePath == null) {
            return null;
        }
        int facts = sourcePath.indexOf("/facts/");
        return facts < 0 ? sourcePath : sourcePath.substring(0, facts);
    }

    private List<GeneratedClaim> enrichAtomicEvidenceReferences(
            JsonNode output,
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        List<GeneratedClaim> normalized = new ArrayList<>(claims.size());
        for (GeneratedClaim claim : claims) {
            if (claim == null
                    || !isFinalContent(claim.getDisposition())
                    || safe(claim.getContentPaths()).isEmpty()) {
                normalized.add(claim);
                continue;
            }
            EvidencePurpose purpose = documentPurpose(
                    claim.getContentPaths(),
                    "$.claims");
            LinkedHashSet<String> evidenceIds =
                    new LinkedHashSet<>(safe(claim.getEvidenceIds()));
            for (String contentPath : claim.getContentPaths()) {
                Predicate<ApprovedEvidenceRecord> atomicEvidence =
                        atomicEvidenceFor(contentPath);
                JsonNode value = output.at(contentPath);
                if (!value.isTextual()
                        || !StringUtils.hasText(value.textValue())) {
                    continue;
                }
                evidenceById.forEach((evidenceId, candidates) -> {
                    boolean exactApprovedFact =
                            atomicEvidence != null
                                    && candidates.stream()
                                            .anyMatch(record ->
                                                    record.purpose().supports(purpose)
                                                            && atomicEvidence.test(record)
                                                            && equalText(
                                                                    value.textValue(),
                                                                    record.value()));
                    boolean exactApprovedNarrative = candidates.stream()
                            .filter(record -> record.purpose().supports(purpose))
                            .filter(record -> !requiresConfirmedCandidateEvidence(
                                    contentPath)
                                    || candidateEvidence(record.source()))
                            .anyMatch(record -> equalText(
                                    value.textValue(),
                                    record.value()));
                    boolean exactSupportedTerm = candidates.stream()
                            .filter(record -> record.purpose().supports(purpose))
                            .filter(record -> !requiresConfirmedCandidateEvidence(
                                    contentPath)
                                    || candidateEvidence(record.source()))
                            .anyMatch(record -> supportsAnySpecificTerm(
                                    value.textValue(),
                                    record.value()));
                    if (exactApprovedFact || exactApprovedNarrative) {
                        evidenceIds.add(evidenceId);
                    }
                    if (exactSupportedTerm) {
                        evidenceIds.add(evidenceId);
                    }
                });
            }
            GeneratedClaim copy = copyWithClaimId(
                    claim,
                    claim.getClaimId());
            copy.setEvidenceIds(List.copyOf(evidenceIds));
            normalized.add(copy);
        }
        return List.copyOf(normalized);
    }

    private boolean supportsAnySpecificTerm(
            String content,
            String evidenceValue
    ) {
        String normalizedEvidence = normalise(evidenceValue);
        return containsAnySupportedMatch(
                NUMERIC_CLAIM.matcher(content),
                normalizedEvidence)
                || containsAnySupportedMatch(
                        SENSITIVE_CLAIM.matcher(content),
                        normalizedEvidence);
    }

    private boolean containsAnySupportedMatch(
            Matcher matcher,
            String evidenceText
    ) {
        while (matcher.find()) {
            String matched = normalise(matcher.group());
            Pattern supported = Pattern.compile(
                    "(?<![\\p{L}\\p{N}])"
                            + Pattern.quote(matched)
                            + "(?![\\p{L}\\p{N}])");
            if (supported.matcher(evidenceText).find()) {
                return true;
            }
        }
        return false;
    }

    private List<GeneratedClaim> normalizeDuplicateClaimIds(
            List<GeneratedClaim> claims
    ) {
        Set<String> reservedClaimIds = new HashSet<>();
        claims.stream()
                .filter(java.util.Objects::nonNull)
                .map(GeneratedClaim::getClaimId)
                .filter(StringUtils::hasText)
                .forEach(reservedClaimIds::add);
        Set<String> usedClaimIds = new HashSet<>();
        List<GeneratedClaim> normalized = new ArrayList<>(claims.size());
        int nextGeneratedClaimNumber = 1000;
        for (GeneratedClaim claim : claims) {
            if (claim == null
                    || !StringUtils.hasText(claim.getClaimId())
                    || usedClaimIds.add(claim.getClaimId())) {
                normalized.add(claim);
                continue;
            }
            String generatedClaimId;
            do {
                generatedClaimId = "CLAIM-" + nextGeneratedClaimNumber++;
            } while (reservedClaimIds.contains(generatedClaimId)
                    || usedClaimIds.contains(generatedClaimId));
            usedClaimIds.add(generatedClaimId);
            normalized.add(copyWithClaimId(claim, generatedClaimId));
        }
        return List.copyOf(normalized);
    }

    private GeneratedClaim copyWithClaimId(
            GeneratedClaim source,
            String claimId
    ) {
        GeneratedClaim copy = new GeneratedClaim();
        copy.setClaimId(claimId);
        copy.setDisposition(source.getDisposition());
        copy.setEvidenceIds(safe(source.getEvidenceIds()));
        copy.setContentPaths(safe(source.getContentPaths()));
        copy.setReviewText(source.getReviewText());
        return copy;
    }

    private List<GeneratedClaim> expandContainerContentPaths(
            List<GeneratedClaim> claims,
            Set<String> expectedPaths
    ) {
        List<GeneratedClaim> normalized = new ArrayList<>(claims.size());
        for (GeneratedClaim claim : claims) {
            if (claim == null || !isFinalContent(claim.getDisposition())) {
                normalized.add(claim);
                continue;
            }
            LinkedHashSet<String> expandedPaths = new LinkedHashSet<>();
            for (String contentPath : safe(claim.getContentPaths())) {
                if (contentPath == null || expectedPaths.contains(contentPath)) {
                    expandedPaths.add(contentPath);
                    continue;
                }
                List<String> descendants = expectedPaths.stream()
                        .filter(expected -> expected.startsWith(contentPath + "/"))
                        .toList();
                if (!descendants.isEmpty()) {
                    expandedPaths.addAll(descendants);
                }
            }
            GeneratedClaim copy = new GeneratedClaim();
            copy.setClaimId(claim.getClaimId());
            copy.setDisposition(claim.getDisposition());
            copy.setEvidenceIds(safe(claim.getEvidenceIds()));
            copy.setContentPaths(List.copyOf(expandedPaths));
            copy.setReviewText(claim.getReviewText());
            normalized.add(copy);
        }
        return List.copyOf(normalized);
    }

    private List<GeneratedClaim> splitOversizedContentPathClaims(
            List<GeneratedClaim> claims
    ) {
        List<GeneratedClaim> normalized = new ArrayList<>();
        for (GeneratedClaim claim : claims) {
            List<String> contentPaths =
                    claim == null ? List.of() : safe(claim.getContentPaths());
            if (contentPaths.size() <= MAX_CLAIM_REFERENCES) {
                normalized.add(claim);
                continue;
            }
            for (int start = 0;
                    start < contentPaths.size();
                    start += MAX_CLAIM_REFERENCES) {
                int end = Math.min(
                        start + MAX_CLAIM_REFERENCES,
                        contentPaths.size());
                GeneratedClaim bounded = copyWithClaimId(
                        claim,
                        claim.getClaimId());
                bounded.setContentPaths(
                        List.copyOf(contentPaths.subList(start, end)));
                normalized.add(bounded);
            }
        }
        return List.copyOf(normalized);
    }

    private List<GeneratedClaim> splitPurposeSpanningClaims(
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        Set<String> allocatedClaimIds = new HashSet<>();
        for (GeneratedClaim claim : claims) {
            if (claim != null && StringUtils.hasText(claim.getClaimId())) {
                allocatedClaimIds.add(claim.getClaimId());
            }
        }

        List<GeneratedClaim> normalized = new ArrayList<>(claims.size());
        int nextGeneratedClaimNumber = 1000;
        for (GeneratedClaim claim : claims) {
            if (claim == null || !isFinalContent(claim.getDisposition())) {
                normalized.add(claim);
                continue;
            }
            List<String> cvPaths = safe(claim.getContentPaths()).stream()
                    .filter(path -> path != null && path.startsWith("/cv/"))
                    .toList();
            List<String> coverLetterPaths = safe(claim.getContentPaths()).stream()
                    .filter(path -> path != null && path.startsWith("/coverLetter/"))
                    .toList();
            if (cvPaths.isEmpty() || coverLetterPaths.isEmpty()) {
                normalized.add(claim);
                continue;
            }

            normalized.add(copyForPurpose(
                    claim,
                    claim.getClaimId(),
                    cvPaths,
                    EvidencePurpose.CV,
                    evidenceById));
            String generatedClaimId;
            do {
                generatedClaimId = "CLAIM-" + nextGeneratedClaimNumber++;
            } while (allocatedClaimIds.contains(generatedClaimId));
            allocatedClaimIds.add(generatedClaimId);
            normalized.add(copyForPurpose(
                    claim,
                    generatedClaimId,
                    coverLetterPaths,
                    EvidencePurpose.COVER_LETTER,
                    evidenceById));
        }
        return List.copyOf(normalized);
    }

    private GeneratedClaim copyForPurpose(
            GeneratedClaim source,
            String claimId,
            List<String> contentPaths,
            EvidencePurpose purpose,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        GeneratedClaim copy = new GeneratedClaim();
        copy.setClaimId(claimId);
        copy.setDisposition(source.getDisposition());
        copy.setEvidenceIds(safe(source.getEvidenceIds()).stream()
                .filter(evidenceId -> {
                    List<ApprovedEvidenceRecord> candidates =
                            evidenceById.get(evidenceId);
                    return candidates == null
                            || candidates.stream()
                                    .anyMatch(record -> record.purpose().supports(purpose));
                })
                .toList());
        copy.setContentPaths(List.copyOf(contentPaths));
        copy.setReviewText(source.getReviewText());
        return copy;
    }

    private boolean isFinalContent(ClaimDisposition disposition) {
        return disposition == ClaimDisposition.SUPPORTED
                || disposition == ClaimDisposition.REWORDED;
    }

    private void validateClaim(
            JsonNode output,
            GeneratedClaim claim,
            int index,
            Set<String> expectedPaths,
            Set<String> coveredPaths,
            Set<String> claimIds,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            boolean versionedEvidence
    ) {
        String claimPath = "$.claims[" + index + "]";
        require(claim != null, claimPath, "claim is missing");
        require(StringUtils.hasText(claim.getClaimId()), claimPath + ".claimId", "claim ID is blank");
        require(claimIds.add(claim.getClaimId()), claimPath + ".claimId", "claim ID is duplicated");
        require(claim.getDisposition() != null, claimPath + ".disposition", "disposition is missing");

        List<String> evidenceIds = safe(claim.getEvidenceIds());
        List<String> contentPaths = safe(claim.getContentPaths());
        require(evidenceIds.size() <= MAX_CLAIM_REFERENCES,
                claimPath + ".evidenceIds",
                "claim exceeds the bounded evidence-reference count");
        require(contentPaths.size() <= MAX_CLAIM_REFERENCES,
                claimPath + ".contentPaths",
                "claim exceeds the bounded content-path count");
        require(claim.getReviewText() == null
                        || claim.getReviewText().length()
                                <= MAX_REVIEW_TEXT_LENGTH,
                claimPath + ".reviewText",
                "claim review text exceeds the bounded length");
        List<List<ApprovedEvidenceRecord>> evidenceCandidates =
                new ArrayList<>();
        Set<String> uniqueEvidenceIds = new HashSet<>();
        for (String evidenceId : evidenceIds) {
            require(StringUtils.hasText(evidenceId), claimPath + ".evidenceIds", "evidence ID is blank");
            require(uniqueEvidenceIds.add(evidenceId),
                    claimPath + ".evidenceIds", "evidence ID is duplicated");
            List<ApprovedEvidenceRecord> candidates =
                    evidenceById.get(evidenceId);
            require(candidates != null,
                    claimPath + ".evidenceIds",
                    "evidence ID is not approved");
            evidenceCandidates.add(candidates);
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

        require(!contentPaths.isEmpty(), claimPath + ".contentPaths", "final claim has no content path");
        require(!StringUtils.hasText(claim.getReviewText()),
                claimPath + ".reviewText", "final claim contains review-only text");
        EvidencePurpose purpose =
                documentPurpose(contentPaths, claimPath);
        List<ApprovedEvidenceRecord> evidence = new ArrayList<>();
        for (List<ApprovedEvidenceRecord> candidates :
                evidenceCandidates) {
            List<ApprovedEvidenceRecord> compatible = candidates.stream()
                    .filter(record -> record.purpose().supports(purpose))
                    .toList();
            require(compatible.size() == 1,
                    claimPath + ".evidenceIds",
                    "evidence ID is not approved for this document purpose");
            evidence.add(compatible.get(0));
        }
        require(!evidence.isEmpty(),
                claimPath + ".evidenceIds",
                "final claim has no approved evidence");
        for (String contentPath : contentPaths) {
            require(expectedPaths.contains(contentPath),
                    claimPath + ".contentPaths", "content path is not an approved final claim path");
            require(coveredPaths.add(contentPath),
                    claimPath + ".contentPaths", "content path is covered more than once");
            JsonNode value = output.at(contentPath);
            require(value.isTextual() && StringUtils.hasText(value.textValue()),
                    claimPath + ".contentPaths", "content path does not contain final text");
            validateEvidenceAlignment(
                    contentPath,
                    value.textValue(),
                    evidence,
                    claimPath,
                    versionedEvidence);
        }
    }

    private void validateEvidenceAlignment(
            String contentPath,
            String content,
            List<ApprovedEvidenceRecord> evidence,
            String claimPath,
            boolean versionedEvidence
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
            if (contentPath.startsWith("/cv/")
                    && (!versionedEvidence
                            || requiresConfirmedCandidateEvidence(
                                    contentPath))) {
                require(evidence.stream().anyMatch(
                                record -> candidateEvidence(
                                        record.source())),
                        claimPath + ".evidenceIds",
                        "candidate claim has no approved profile evidence");
            }
            if (versionedEvidence
                    && requiresConfirmedCandidateEvidence(contentPath)) {
                require(evidence.stream().anyMatch(
                                record -> candidateEvidence(
                                        record.source())),
                        claimPath + ".evidenceIds",
                        "candidate claim has no confirmed claimant evidence");
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
                "numeric claim is absent from approved evidence at "
                        + contentPath);
        requireMatchesAreSupported(
                SENSITIVE_CLAIM.matcher(content),
                evidenceText,
                claimPath,
                "sensitive or specific claim is absent from approved evidence at "
                        + contentPath);
    }

    private Predicate<ApprovedEvidenceRecord> atomicEvidenceFor(String path) {
        if (path.equals("/cv/targetRole")) {
            return record -> record.evidenceId().startsWith("PROFILE.TARGET_ROLE.")
                    || record.evidenceId().equals("JOB.TITLE");
        }
        if (path.matches("/cv/coreSkills/\\d+/name")) {
            return record -> record.evidenceId().startsWith("PROFILE.SKILL.")
                    || factType(record, "DEMONSTRATED_SKILL");
        }
        if (path.matches("/cv/qualifications/\\d+/qualificationName")) {
            return record -> suffix(".NAME").test(record)
                    || factType(record, "QUALIFICATION_TITLE");
        }
        if (path.matches("/cv/qualifications/\\d+/issuingBody")) {
            return record -> suffix(".ISSUING_BODY").test(record)
                    || factType(record, "ISSUER", "INSTITUTION");
        }
        if (path.matches("/cv/qualifications/\\d+/status")) {
            return record -> suffix(".STATUS").test(record)
                    || factType(
                            record,
                            "STATUS",
                            "RESULT",
                            "RESULT_OR_STATUS");
        }
        if (path.matches("/cv/qualifications/\\d+/grade")) {
            return record -> suffix(".GRADE").test(record)
                    || factType(
                            record,
                            "RESULT",
                            "RESULT_OR_STATUS");
        }
        if (path.matches("/cv/qualifications/\\d+/dateAchieved")) {
            return record -> suffix(".DATE_ACHIEVED").test(record)
                    || factType(record, "ISSUE_DATE");
        }
        if (path.matches("/cv/qualifications/\\d+/expectedCompletion")) {
            return record -> suffix(".EXPECTED_COMPLETION").test(record)
                    || factType(record, "EXPECTED_COMPLETION", "END_DATE");
        }
        if (path.matches("/cv/workHistory/\\d+/jobTitle")) {
            return record -> employmentSuffix(".JOB_TITLE").test(record)
                    || factType(
                            record,
                            "ROLE_TITLE",
                            "PROJECT_ROLE",
                            "HEADING");
        }
        if (path.matches("/cv/workHistory/\\d+/employer")) {
            return record -> employmentSuffix(".EMPLOYER").test(record)
                    || factType(
                            record,
                            "ORGANISATION",
                            "ORGANISATION_CONTEXT",
                            "INSTITUTION");
        }
        if (path.matches("/cv/workHistory/\\d+/startDate")) {
            return record -> employmentSuffix(".START_DATE").test(record)
                    || factType(record, "START_DATE");
        }
        if (path.matches("/cv/workHistory/\\d+/endDate")) {
            return record -> employmentSuffix(".END_DATE").test(record)
                    || factType(record, "END_DATE");
        }
        if (path.equals("/coverLetter/jobTitle")) {
            return record -> record.evidenceId().equals("JOB.TITLE");
        }
        if (path.equals("/coverLetter/companyName")) {
            return record -> record.evidenceId().equals("JOB.COMPANY");
        }
        return null;
    }

    private EvidencePurpose documentPurpose(
            List<String> contentPaths,
            String claimPath) {
        Set<EvidencePurpose> purposes = new HashSet<>();
        for (String contentPath : contentPaths) {
            if (contentPath.startsWith("/cv/")) {
                purposes.add(EvidencePurpose.CV);
            } else if (contentPath.startsWith("/coverLetter/")) {
                purposes.add(EvidencePurpose.COVER_LETTER);
            }
        }
        require(purposes.size() == 1,
                claimPath + ".contentPaths",
                "a claim cannot span document purposes");
        return purposes.iterator().next();
    }

    private boolean candidateEvidence(EvidenceSource source) {
        return source == EvidenceSource.PROFILE
                || source == EvidenceSource.EVIDENCE_SNAPSHOT;
    }

    private boolean requiresConfirmedCandidateEvidence(String path) {
        return !path.equals("/cv/title")
                && !path.equals("/cv/targetRole")
                && !path.equals("/coverLetter/title")
                && !path.equals("/coverLetter/jobTitle")
                && !path.equals("/coverLetter/companyName");
    }

    private boolean factType(
            ApprovedEvidenceRecord record,
            String... allowed) {
        if (record.factType() == null) {
            return false;
        }
        return java.util.Arrays.asList(allowed)
                .contains(record.factType());
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
