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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    static final String POLICY_VERSION = "2.24.0";
    static final String CORE_SKILL_PROJECTION_POLICY_VERSION = "2.11.0";
    static final String ROLLBACK_POLICY_VERSION = "2.10.0";
    private static final int MAX_CLAIMS = 200;
    private static final int MAX_CLAIM_REFERENCES = 30;
    private static final int MAX_REVIEW_TEXT_LENGTH = 500;
    private static final int LEGACY_MIN_PROJECTED_CORE_SKILLS = 8;
    private static final int MAX_PROJECTED_CORE_SKILLS = 12;
    private static final String GENERATION_INTENT_EVIDENCE_ID =
            "REQUEST.GENERATION_INTENT";
    private static final String JOB_TITLE_EVIDENCE_ID = "JOB.TITLE";
    private static final String JOB_COMPANY_EVIDENCE_ID = "JOB.COMPANY";
    private static final String OPENING_PARAGRAPH_PATH =
            "/coverLetter/openingParagraph";
    private static final String CLOSING_PARAGRAPH_PATH =
            "/coverLetter/closingParagraph";
    private static final String BODY_PARAGRAPHS_PATH =
            "/coverLetter/bodyParagraphs";

    private static final Pattern NUMERIC_CLAIM =
            Pattern.compile("(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?%?(?![\\p{L}\\p{N}])");
    private static final Pattern SENSITIVE_CLAIM = Pattern.compile(
            "(?i)\\b(?:right to work|work authori[sz]ation|visa|sponsorship"
                    + "|available (?:immediately|from)|notice period"
                    + "|salary|compensation|£\\s*\\d+|\\$\\s*\\d+"
                    + "|phd|doctorate|mba|master'?s degree"
                    + "|aws|amazon web services|azure|gcp|google cloud"
                    + "|docker|kubernetes|terraform|react|angular|python|java|spring)\\b");
    private static final Pattern MOTIVATIONAL_TONE = Pattern.compile(
            "(?i)\\b(?:passionate|enthusiastic|excited|motivated|keen)\\b");
    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,63}\\b");
    private static final Pattern UK_POSTCODE = Pattern.compile(
            "(?i)\\b(?:GIR\\s?0AA|(?:[A-Z]{1,2}\\d[A-Z\\d]?|[A-Z]{2}\\d{1,2})"
                    + "\\s?\\d[A-Z]{2})\\b");
    private static final Pattern BIOGRAPHICAL_BOILERPLATE = Pattern.compile(
            "(?i)\\b(?:john doe|jane doe|references? available (?:upon|on) request"
                    + "|(?:inclusive|executive|people|strategic) leadership"
                    + "|(?:people|programme|program|project|product|engineering) management)\\b");
    private static final Pattern CANDIDATE_FACTUAL_PROSE = Pattern.compile(
            "(?i)\\b(?:my\\s+(?:experience|background|skills|expertise|qualifications?"
                    + "|track record)|i\\s+(?:have|hold|possess|bring|offer|worked|built"
                    + "|developed|implemented|delivered|created|designed|managed|led"
                    + "|achieved|supported|served|used|speciali[sz]e|am\\s+(?!(?:passionate"
                    + "|enthusiastic|excited|motivated|keen)\\b)))");
    private static final Pattern PROJECT_CONTENT_PATH =
            Pattern.compile("^/cv/projects/(\\d+)/.+$");

    public void validate(
            JsonNode output,
            GeneratedApplicationDocuments documents,
            ClaimEvidenceCatalog catalog
    ) {
        validate(output, documents, catalog, false, false);
    }

    public void validate(
            JsonNode output,
            GeneratedApplicationDocuments documents,
            ClaimEvidenceCatalog catalog,
            boolean enforceCanonicalApplicationBookends
    ) {
        validate(
                output,
                documents,
                catalog,
                enforceCanonicalApplicationBookends,
                false,
                false);
    }

    public void validate(
            JsonNode output,
            GeneratedApplicationDocuments documents,
            ClaimEvidenceCatalog catalog,
            boolean enforceCanonicalApplicationBookends,
            boolean projectCoreSkills
    ) {
        validate(
                output,
                documents,
                catalog,
                enforceCanonicalApplicationBookends,
                projectCoreSkills,
                false,
                projectCoreSkills);
    }

    public void validate(
            JsonNode output,
            GeneratedApplicationDocuments documents,
            ClaimEvidenceCatalog catalog,
            boolean enforceCanonicalApplicationBookends,
            boolean projectCoreSkills,
            boolean useAdaptiveCoreSkillBudget
    ) {
        validate(
                output,
                documents,
                catalog,
                enforceCanonicalApplicationBookends,
                projectCoreSkills,
                useAdaptiveCoreSkillBudget,
                useAdaptiveCoreSkillBudget);
    }

    public void validate(
            JsonNode output,
            GeneratedApplicationDocuments documents,
            ClaimEvidenceCatalog catalog,
            boolean enforceCanonicalApplicationBookends,
            boolean projectCoreSkills,
            boolean useAdaptiveCoreSkillBudget,
            boolean useCanonicalProfileSkills
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
        boolean versionedEvidence =
                "2.0".equals(catalog.catalogVersion());
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
            if (versionedEvidence) {
                if (record.source() == EvidenceSource.PROFILE
                        || record.evidenceId().startsWith("PROFILE.")) {
                    throw new IllegalStateException(
                            "Versioned evidence catalogue contains positional profile evidence.");
                }
                if (record.source()
                        == EvidenceSource.EVIDENCE_SNAPSHOT) {
                    requireStableVersionedEvidenceId(record);
                }
                if (record.source() == EvidenceSource.PROFILE_REVISION) {
                    requireStableVersionedEvidenceId(record);
                    if (record.purpose() != EvidencePurpose.CV
                            || !"DECLARED_SKILL".equals(record.factType())
                            || !"PROFILE_SKILLS".equals(record.category())
                            || record.sourcePath() == null
                            || !record.sourcePath().matches(
                                    "/profile/skills/\\d+")) {
                        throw new IllegalStateException(
                                "Versioned profile-revision evidence is not a bounded declared skill.");
                    }
                }
            }
            evidenceById
                    .computeIfAbsent(
                            record.evidenceId(),
                            ignored -> new ArrayList<>())
                    .add(record);
        }
        validateSubmittedEvidence(
                documents.getClaims(),
                evidenceById);
        if (useAdaptiveCoreSkillBudget) {
            validateSubmittedPersonalSummaryEvidence(
                    documents.getClaims(),
                    evidenceById);
            validateAdaptiveClaimLedger(
                    output,
                    documents,
                    catalog.records(),
                    evidenceById,
                    versionedEvidence,
                    enforceCanonicalApplicationBookends,
                    useCanonicalProfileSkills);
            return;
        }
        List<GeneratedClaim> submittedClaims =
                normalizeCompleteOneBasedBodyParagraphPaths(
                        output,
                        documents.getClaims(),
                        versionedEvidence);
        submittedClaims = normalizeCompleteOneBasedNestedTextArrayPaths(
                output,
                submittedClaims,
                versionedEvidence);
        Map<String, String> projectedCoreSkillEvidenceByPath = Map.of();
        if (projectCoreSkills) {
            projectedCoreSkillEvidenceByPath = canonicalizeCoreSkills(
                    output,
                    catalog.records(),
                    versionedEvidence,
                    useCanonicalProfileSkills);
            submittedClaims = removeSubmittedCoreSkillCoverage(
                    submittedClaims);
        }
        documents.setClaims(submittedClaims);
        Set<String> submittedPaths = claimBearingPaths(output);
        validateSubmittedContentPaths(
                submittedClaims,
                submittedPaths,
                claimContentTopologyPaths(output));
        canonicalizeCitedAtomicContent(
                output,
                expandContainerContentPaths(
                        documents.getClaims(),
                        submittedPaths),
                evidenceById);
        canonicalizeOptionalAtomicContent(output, evidenceById);
        canonicalizeDocumentIdentity(output, evidenceById);
        pruneUnsupportedWorkHistory(output, evidenceById);
        canonicalizeUnclaimedProjectDescriptions(
                output,
                documents.getClaims(),
                evidenceById);
        canonicalizeUnclaimedApplicationBookends(
                output,
                documents.getClaims(),
                evidenceById,
                versionedEvidence);
        Set<String> expectedPaths = claimBearingPaths(output);
        List<GeneratedClaim> claims = normalizeDuplicateClaimIds(
                normalizeDuplicateCoverage(
                        splitPurposeSpanningClaims(
                                expandContainerContentPaths(
                                        documents.getClaims(),
                                        expectedPaths),
                                evidenceById)));
        claims = addExactCoverageClaims(
                output,
                claims,
                evidenceById,
                expectedPaths,
                versionedEvidence,
                projectedCoreSkillEvidenceByPath);
        claims = enrichAtomicEvidenceReferences(
                output,
                claims,
                evidenceById,
                projectCoreSkills,
                false);
        claims = normalizeDuplicateClaimIds(
                splitOversizedContentPathClaims(
                        isolateStructuredProjectClaims(
                                output,
                                claims,
                                evidenceById,
                                versionedEvidence)));
        documents.setClaims(claims);
        validateFinalClaimLedger(
                output,
                claims,
                expectedPaths,
                evidenceById,
                versionedEvidence,
                enforceCanonicalApplicationBookends);
    }

    private void requireStableVersionedEvidenceId(
            ApprovedEvidenceRecord record
    ) {
        try {
            java.util.UUID.fromString(record.evidenceId());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Versioned evidence catalogue contains a non-stable fact ID.");
        }
    }

    static String policyVersion(
            boolean projectsCoreSkills,
            boolean usesAdaptiveCoreSkillBudget
    ) {
        if (usesAdaptiveCoreSkillBudget) {
            return POLICY_VERSION;
        }
        return projectsCoreSkills
                ? CORE_SKILL_PROJECTION_POLICY_VERSION
                : ROLLBACK_POLICY_VERSION;
    }

    private void validateAdaptiveClaimLedger(
            JsonNode output,
            GeneratedApplicationDocuments documents,
            List<ApprovedEvidenceRecord> records,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            boolean versionedEvidence,
            boolean enforceCanonicalApplicationBookends,
            boolean useCanonicalProfileSkills
    ) {
        List<GeneratedClaim> submittedClaims =
                normalizeCompleteOneBasedBodyParagraphPaths(
                        output,
                        documents.getClaims(),
                        versionedEvidence);
        submittedClaims = normalizeCompleteOneBasedNestedTextArrayPaths(
                output,
                submittedClaims,
                versionedEvidence);
        submittedClaims = removeSubmittedCoreSkillCoverage(submittedClaims);

        Set<String> submittedPaths = claimBearingPaths(output);
        submittedClaims = removeUnpopulatedFinalClaimPaths(
                submittedClaims,
                submittedPaths,
                claimContentTopologyPaths(output));
        documents.setClaims(submittedClaims);
        validateSubmittedContentPaths(
                submittedClaims,
                submittedPaths,
                claimContentTopologyPaths(output));
        canonicalizeCitedAtomicContent(
                output,
                expandContainerContentPaths(
                        submittedClaims,
                        submittedPaths),
                evidenceById);
        canonicalizeOptionalAtomicContent(output, evidenceById);
        canonicalizeDocumentIdentity(output, evidenceById);
        pruneUnsupportedWorkHistory(output, evidenceById);
        canonicalizeUnclaimedProjectDescriptions(
                output,
                submittedClaims,
                evidenceById);
        clearUnclaimedTailoredDescriptions(output, submittedClaims);
        canonicalizeUnclaimedApplicationBookends(
                output,
                submittedClaims,
                evidenceById,
                versionedEvidence);
        Set<String> nonSkillExpectedPaths = claimBearingPaths(output).stream()
                .filter(path -> !isProjectedCoreSkillPath(path))
                .filter(path -> !isCanonicalDocumentTitlePath(path))
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        List<GeneratedClaim> claims = normalizeDuplicateClaimIds(
                normalizeDuplicateCoverage(
                        splitPurposeSpanningClaims(
                                expandContainerContentPaths(
                                        submittedClaims,
                                        nonSkillExpectedPaths),
                                evidenceById)));
        claims = addExactCoverageClaims(
                output,
                claims,
                evidenceById,
                nonSkillExpectedPaths,
                versionedEvidence,
                Map.of());
        claims = enrichAtomicEvidenceReferences(
                output,
                claims,
                evidenceById,
                false,
                true);
        claims = normalizeDuplicateClaimIds(
                splitOversizedContentPathClaims(
                        isolateStructuredProjectClaims(
                                output,
                                claims,
                                evidenceById,
                                versionedEvidence)));
        if (output.at("/cv/title").isTextual()
                && output.at("/cv/targetRole").isTextual()) {
            claims = ensureCanonicalTitleCoverage(
                    claims,
                    evidenceById,
                    "/cv/title",
                    "/cv/targetRole",
                    EvidencePurpose.CV);
        }
        if (output.at("/coverLetter/title").isTextual()
                && output.at("/coverLetter/jobTitle").isTextual()) {
            claims = ensureCanonicalTitleCoverage(
                    claims,
                    evidenceById,
                    "/coverLetter/title",
                    "/coverLetter/jobTitle",
                    EvidencePurpose.COVER_LETTER);
        }
        claims = normalizeDuplicateClaimIds(claims);

        int remainingClaimCapacity = MAX_CLAIMS - claims.size();
        Map<String, String> projectedCoreSkillEvidenceByPath =
                canonicalizeCoreSkills(
                        output,
                        records,
                        versionedEvidence,
                        remainingClaimCapacity,
                        useCanonicalProfileSkills);
        canonicalizeUnsupportedPersonalSummaryNumerics(
                output,
                documents,
                claims,
                evidenceById,
                projectedCoreSkillEvidenceByPath,
                versionedEvidence);
        Set<String> expectedPaths = claimBearingPaths(output);
        claims = addExactCoverageClaims(
                output,
                claims,
                evidenceById,
                expectedPaths,
                versionedEvidence,
                projectedCoreSkillEvidenceByPath);
        claims = enrichAtomicEvidenceReferences(
                output,
                claims,
                evidenceById,
                true,
                true);
        claims = normalizeDuplicateClaimIds(claims);
        canonicalizeUnsupportedFinalContribution(
                output,
                documents,
                claims,
                evidenceById,
                versionedEvidence);
        documents.setClaims(claims);
        validateFinalClaimLedger(
                output,
                claims,
                expectedPaths,
                evidenceById,
                versionedEvidence,
                enforceCanonicalApplicationBookends);
    }

    private void canonicalizeUnsupportedFinalContribution(
            JsonNode output,
            GeneratedApplicationDocuments documents,
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            boolean versionedEvidence
    ) {
        if (!versionedEvidence
                || documents.getCoverLetter() == null
                || documents.getCoverLetter().getBodyParagraphs() == null
                || !(output.at(BODY_PARAGRAPHS_PATH)
                        instanceof ArrayNode paragraphs)
                || paragraphs.isEmpty()) {
            return;
        }
        int finalIndex = paragraphs.size() - 1;
        String path = BODY_PARAGRAPHS_PATH + "/" + finalIndex;
        List<GeneratedClaim> matchingClaims = claims.stream()
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> safe(candidate.getContentPaths())
                        .contains(path))
                .toList();
        if (matchingClaims.isEmpty()) {
            return;
        }
        String current = paragraphs.get(finalIndex).asText("");
        String jobTitle = exactEvidenceValue(
                evidenceById,
                JOB_TITLE_EVIDENCE_ID);
        if (!StringUtils.hasText(jobTitle)) {
            return;
        }
        boolean repaired = false;
        for (GeneratedClaim claim : matchingClaims) {
            List<ApprovedEvidenceRecord> evidence = safe(
                    claim.getEvidenceIds()).stream()
                    .flatMap(id -> evidenceById.getOrDefault(
                            id,
                            List.of()).stream())
                    .toList();
            if (evidence.stream().anyMatch(record -> candidateEvidence(
                            record.source()))
                    || isNonFactualMotivationalCoverLetterProse(
                            path,
                            current,
                            evidence)) {
                continue;
            }
            claim.setDisposition(ClaimDisposition.SUPPORTED);
            claim.setEvidenceIds(List.of(JOB_TITLE_EVIDENCE_ID));
            claim.setReviewText("");
            repaired = true;
        }
        if (!repaired) {
            return;
        }
        String canonical = "I am keen to contribute to this role.";
        paragraphs.set(
                finalIndex,
                paragraphs.textNode(canonical));
        documents.getCoverLetter().getBodyParagraphs().set(
                finalIndex,
                canonical);
    }

    private void canonicalizeUnsupportedPersonalSummaryNumerics(
            JsonNode output,
            GeneratedApplicationDocuments documents,
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            Map<String, String> projectedCoreSkillEvidenceByPath,
            boolean versionedEvidence
    ) {
        String path = "/cv/personalSummary";
        JsonNode summaryNode = output.at(path);
        if (!versionedEvidence
                || documents.getCv() == null
                || !summaryNode.isTextual()
                || !NUMERIC_CLAIM.matcher(summaryNode.textValue()).find()) {
            return;
        }
        List<GeneratedClaim> matchingClaims = claims.stream()
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> safe(candidate.getContentPaths())
                        .contains(path))
                .toList();
        if (matchingClaims.isEmpty()) {
            return;
        }
        String evidenceText = matchingClaims.stream()
                .flatMap(claim -> safe(claim.getEvidenceIds()).stream())
                .flatMap(id -> evidenceById.getOrDefault(id, List.of()).stream())
                .map(ApprovedEvidenceRecord::value)
                .filter(StringUtils::hasText)
                .map(this::normalise)
                .distinct()
                .collect(java.util.stream.Collectors.joining(" "));
        if (!hasUnsupportedMatch(
                NUMERIC_CLAIM.matcher(summaryNode.textValue()),
                evidenceText)) {
            return;
        }
        ApprovedEvidenceRecord skill = projectedCoreSkillEvidenceByPath.values()
                .stream()
                .distinct()
                .flatMap(id -> evidenceById.getOrDefault(id, List.of()).stream())
                .filter(record -> candidateEvidence(record.source()))
                .filter(record -> "DEMONSTRATED_SKILL".equals(
                        record.factType()))
                .filter(record -> StringUtils.hasText(record.value()))
                .filter(record -> !NUMERIC_CLAIM.matcher(record.value()).find())
                .findFirst()
                .orElse(null);
        if (skill == null) {
            return;
        }
        String canonical = "Profile-backed skills include "
                + skill.value().trim()
                + ".";
        replaceText(output, path, canonical);
        documents.getCv().setPersonalSummary(canonical);
        for (GeneratedClaim claim : matchingClaims) {
            claim.setDisposition(ClaimDisposition.SUPPORTED);
            claim.setEvidenceIds(List.of(skill.evidenceId()));
            claim.setReviewText("");
        }
    }

    private boolean hasUnsupportedMatch(
            Matcher matcher,
            String evidenceText
    ) {
        while (matcher.find()) {
            String matched = normalise(matcher.group());
            Pattern supported = Pattern.compile(
                    "(?<![\\p{L}\\p{N}])"
                            + Pattern.quote(matched)
                            + "(?![\\p{L}\\p{N}])");
            if (!supported.matcher(evidenceText).find()) {
                return true;
            }
        }
        return false;
    }

    private void validateFinalClaimLedger(
            JsonNode output,
            List<GeneratedClaim> claims,
            Set<String> expectedPaths,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            boolean versionedEvidence,
            boolean enforceCanonicalApplicationBookends
    ) {
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
                    versionedEvidence,
                    enforceCanonicalApplicationBookends);
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

    private Map<String, String> canonicalizeCoreSkills(
            JsonNode output,
            List<ApprovedEvidenceRecord> records,
            boolean versionedEvidence,
            boolean useCanonicalProfileSkills
    ) {
        return canonicalizeCoreSkills(
                output,
                records,
                versionedEvidence,
                MAX_PROJECTED_CORE_SKILLS,
                useCanonicalProfileSkills);
    }

    private Map<String, String> canonicalizeCoreSkills(
            JsonNode output,
            List<ApprovedEvidenceRecord> records,
            boolean versionedEvidence,
            int remainingClaimCapacity,
            boolean useCanonicalProfileSkills
    ) {
        JsonNode coreSkills = output.at("/cv/coreSkills");
        if (!(coreSkills instanceof ArrayNode coreSkillArray)) {
            return Map.of();
        }

        Map<String, ApprovedEvidenceRecord> availableByNormalisedValue =
                new LinkedHashMap<>();
        for (ApprovedEvidenceRecord record : records) {
            if (!approvedCoreSkill(
                    record,
                    versionedEvidence,
                    useCanonicalProfileSkills)) {
                continue;
            }
            String normalisedValue = normalise(record.value());
            ApprovedEvidenceRecord existing =
                    availableByNormalisedValue.get(normalisedValue);
            if (existing == null
                    || isDemonstratedSkill(record)
                            && !isDemonstratedSkill(existing)) {
                availableByNormalisedValue.put(
                        normalisedValue,
                        record);
            }
        }

        int projectionLimit = Math.min(
                MAX_PROJECTED_CORE_SKILLS,
                Math.max(0, remainingClaimCapacity));
        boolean useTargetedCanonicalSkills =
                useCanonicalProfileSkills && versionedEvidence;
        int minimum = useTargetedCanonicalSkills
                ? 0
                : Math.min(
                        LEGACY_MIN_PROJECTED_CORE_SKILLS,
                        availableByNormalisedValue.size());
        require(
                projectionLimit >= minimum,
                "$.cv.coreSkills",
                "remaining claim capacity cannot preserve the legacy minimum core-skill projection");

        List<ApprovedEvidenceRecord> selected = new ArrayList<>();
        Set<String> selectedValues = new LinkedHashSet<>();
        for (JsonNode proposedSkill : coreSkillArray) {
            if (selected.size() >= projectionLimit) {
                break;
            }
            JsonNode proposedName = proposedSkill.path("name");
            if (!proposedName.isTextual()) {
                continue;
            }
            ApprovedEvidenceRecord exact = availableByNormalisedValue.values()
                    .stream()
                    .filter(record -> record.value().equals(
                            proposedName.textValue()))
                    .findFirst()
                    .orElse(null);
            if (exact == null
                    || !selectedValues.add(normalise(exact.value()))) {
                continue;
            }
            selected.add(exact);
        }

        if (!useTargetedCanonicalSkills) {
            for (ApprovedEvidenceRecord available :
                    availableByNormalisedValue.values()) {
                if (selected.size() >= minimum
                        || selected.size() >= projectionLimit) {
                    break;
                }
                if (selectedValues.add(normalise(available.value()))) {
                    selected.add(available);
                }
            }
        } else {
            ensureJobRelevantCoreSkill(
                    records,
                    availableByNormalisedValue,
                    selected,
                    selectedValues,
                    projectionLimit);
        }

        coreSkillArray.removeAll();
        Map<String, String> evidenceByProjectedPath =
                new LinkedHashMap<>();
        for (int index = 0; index < selected.size(); index++) {
            ApprovedEvidenceRecord skill = selected.get(index);
            ObjectNode projected = coreSkillArray.addObject();
            projected.put("name", skill.value());
            projected.put("evidence", "");
            evidenceByProjectedPath.put(
                    "/cv/coreSkills/" + index + "/name",
                    skill.evidenceId());
        }
        return evidenceByProjectedPath;
    }

    private void ensureJobRelevantCoreSkill(
            List<ApprovedEvidenceRecord> records,
            Map<String, ApprovedEvidenceRecord> availableByNormalisedValue,
            List<ApprovedEvidenceRecord> selected,
            Set<String> selectedValues,
            int projectionLimit
    ) {
        if (projectionLimit == 0 || availableByNormalisedValue.isEmpty()) {
            return;
        }
        String jobContext = records.stream()
                .filter(record -> record.source() == EvidenceSource.JOB)
                .filter(record -> "/job/title".equals(record.sourcePath())
                        || "/job/description".equals(record.sourcePath()))
                .map(ApprovedEvidenceRecord::value)
                .filter(StringUtils::hasText)
                .map(this::normalise)
                .collect(java.util.stream.Collectors.joining(" "));
        if (!StringUtils.hasText(jobContext)) {
            return;
        }
        List<ApprovedEvidenceRecord> relevant =
                availableByNormalisedValue.values().stream()
                        .filter(record -> containsWholeTerm(
                                jobContext,
                                normalise(record.value())))
                        .toList();
        if (relevant.isEmpty()
                || selected.stream().anyMatch(relevant::contains)) {
            return;
        }
        ApprovedEvidenceRecord required = relevant.get(0);
        String requiredValue = normalise(required.value());
        if (selectedValues.contains(requiredValue)) {
            return;
        }
        if (selected.size() >= projectionLimit) {
            ApprovedEvidenceRecord removed = selected.remove(selected.size() - 1);
            selectedValues.remove(normalise(removed.value()));
        }
        selected.add(required);
        selectedValues.add(requiredValue);
    }

    private boolean containsWholeTerm(String text, String term) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(term)) {
            return false;
        }
        return Pattern.compile(
                        "(?<![\\p{L}\\p{N}])"
                                + Pattern.quote(term)
                                + "(?![\\p{L}\\p{N}])")
                .matcher(text)
                .find();
    }

    private boolean isProjectedCoreSkillPath(String path) {
        return path != null
                && path.matches("/cv/coreSkills/\\d+/name");
    }

    private boolean isCanonicalDocumentTitlePath(String path) {
        return "/cv/title".equals(path)
                || "/coverLetter/title".equals(path);
    }

    private boolean approvedCoreSkill(
            ApprovedEvidenceRecord record,
            boolean versionedEvidence,
            boolean useCanonicalProfileSkills
    ) {
        if (record == null || !StringUtils.hasText(record.value())) {
            return false;
        }
        if (versionedEvidence) {
            return record.purpose() == EvidencePurpose.CV
                    && (isDemonstratedSkill(record)
                            || useCanonicalProfileSkills
                                    && record.source()
                                    == EvidenceSource.PROFILE_REVISION
                                    && "DECLARED_SKILL".equals(
                                            record.factType()));
        }
        return record.source() == EvidenceSource.PROFILE
                && record.purpose().supports(EvidencePurpose.CV)
                && record.evidenceId().startsWith("PROFILE.SKILL.");
    }

    private boolean isDemonstratedSkill(
            ApprovedEvidenceRecord record
    ) {
        return record != null
                && record.source() == EvidenceSource.EVIDENCE_SNAPSHOT
                && "DEMONSTRATED_SKILL".equals(record.factType());
    }

    private List<GeneratedClaim> removeSubmittedCoreSkillCoverage(
            List<GeneratedClaim> claims
    ) {
        List<GeneratedClaim> normalized = new ArrayList<>(claims.size());
        for (GeneratedClaim claim : claims) {
            if (claim == null) {
                normalized.add(null);
                continue;
            }
            List<String> retainedPaths = safe(claim.getContentPaths()).stream()
                    .filter(path -> path == null
                            || !path.equals("/cv/coreSkills")
                                    && !path.startsWith("/cv/coreSkills/"))
                    .toList();
            if (isFinalContent(claim.getDisposition())
                    && retainedPaths.isEmpty()) {
                continue;
            }
            GeneratedClaim copy = copyWithClaimId(
                    claim,
                    claim.getClaimId());
            copy.setContentPaths(retainedPaths);
            normalized.add(copy);
        }
        return List.copyOf(normalized);
    }

    private void validateSubmittedEvidence(
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        for (int claimIndex = 0;
                claimIndex < claims.size();
                claimIndex++) {
            GeneratedClaim claim = claims.get(claimIndex);
            if (claim == null) {
                continue;
            }
            Set<String> uniqueEvidenceIds =
                    new HashSet<>();
            List<String> evidenceIds =
                    safe(claim.getEvidenceIds());
            for (int evidenceIndex = 0;
                    evidenceIndex < evidenceIds.size();
                    evidenceIndex++) {
                String evidenceId =
                        evidenceIds.get(evidenceIndex);
                String evidencePath =
                        "$.claims["
                                + claimIndex
                                + "].evidenceIds["
                                + evidenceIndex
                                + "]";
                require(
                        StringUtils.hasText(evidenceId),
                        evidencePath,
                        "evidence ID is blank");
                require(
                        uniqueEvidenceIds.add(evidenceId),
                        evidencePath,
                        "evidence ID is duplicated");
                require(
                        evidenceById.containsKey(evidenceId),
                        evidencePath,
                        "evidence ID is not approved");
                require(
                        evidenceById.get(evidenceId).stream().noneMatch(
                                record -> record.source()
                                        == EvidenceSource.PROFILE_REVISION),
                        evidencePath,
                        "revision-declared skill evidence is service-projected only");
            }
        }
    }

    private void validateSubmittedPersonalSummaryEvidence(
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        for (int claimIndex = 0; claimIndex < claims.size(); claimIndex++) {
            GeneratedClaim claim = claims.get(claimIndex);
            if (claim == null
                    || !isFinalContent(claim.getDisposition())
                    || !safe(claim.getContentPaths()).contains(
                            "/cv/personalSummary")) {
                continue;
            }
            boolean hasConfirmedClaimantEvidence = safe(claim.getEvidenceIds())
                    .stream()
                    .flatMap(evidenceId -> evidenceById
                            .getOrDefault(evidenceId, List.of())
                            .stream())
                    .anyMatch(record -> record.purpose()
                                    .supports(EvidencePurpose.CV)
                            && candidateEvidence(record.source()));
            require(
                    hasConfirmedClaimantEvidence,
                    "$.claims[" + claimIndex + "].evidenceIds",
                    "candidate claim has no confirmed claimant evidence");
        }
    }

    private void validateSubmittedContentPaths(
            List<GeneratedClaim> claims,
            Set<String> submittedPaths,
            Set<String> contentPathTopology
    ) {
        for (int claimIndex = 0;
                claimIndex < claims.size();
                claimIndex++) {
            GeneratedClaim claim = claims.get(claimIndex);
            if (claim == null) {
                continue;
            }
            List<String> contentPaths =
                    safe(claim.getContentPaths());
            boolean coversPopulatedContent = false;
            for (int contentPathIndex = 0;
                    contentPathIndex < contentPaths.size();
                    contentPathIndex++) {
                String contentPath =
                        contentPaths.get(contentPathIndex);
                require(
                        contentPath != null
                                && contentPathTopology
                                        .contains(contentPath),
                        "$.claims["
                                + claimIndex
                                + "].contentPaths["
                                + contentPathIndex
                                + "]",
                        "content path is not an approved final claim path: "
                                + boundedStructuralPath(contentPath));
                coversPopulatedContent =
                        coversPopulatedContent
                                || submittedPaths.contains(contentPath)
                                || submittedPaths.stream()
                                        .anyMatch(path ->
                                                path.startsWith(
                                                        contentPath + "/"));
            }
            if (isFinalContent(claim.getDisposition())
                    && !contentPaths.isEmpty()) {
                require(
                        coversPopulatedContent,
                        "$.claims["
                                + claimIndex
                                + "].contentPaths",
                        "final claim has no populated content path");
            }
        }
    }

    private List<GeneratedClaim> removeUnpopulatedFinalClaimPaths(
            List<GeneratedClaim> claims,
            Set<String> submittedPaths,
            Set<String> contentPathTopology
    ) {
        List<GeneratedClaim> normalized = new ArrayList<>(claims.size());
        for (GeneratedClaim claim : claims) {
            if (claim == null || !isFinalContent(claim.getDisposition())) {
                normalized.add(claim);
                continue;
            }
            List<String> originalPaths = safe(claim.getContentPaths());
            if (originalPaths.isEmpty()) {
                normalized.add(claim);
                continue;
            }
            List<String> retainedPaths = originalPaths.stream()
                    .filter(path -> path == null
                            || !contentPathTopology.contains(path)
                            || submittedPaths.contains(path)
                            || submittedPaths.stream().anyMatch(populatedPath ->
                                    populatedPath.startsWith(path + "/")))
                    .toList();
            if (retainedPaths.isEmpty()) {
                continue;
            }
            if (retainedPaths.size() == originalPaths.size()) {
                normalized.add(claim);
                continue;
            }
            GeneratedClaim copy = copyWithClaimId(
                    claim,
                    claim.getClaimId());
            copy.setContentPaths(retainedPaths);
            normalized.add(copy);
        }
        return List.copyOf(normalized);
    }

    private List<GeneratedClaim> normalizeCompleteOneBasedBodyParagraphPaths(
            JsonNode output,
            List<GeneratedClaim> claims,
            boolean versionedEvidence
    ) {
        if (!versionedEvidence) {
            return claims;
        }
        JsonNode bodyParagraphs = output.at(BODY_PARAGRAPHS_PATH);
        if (!bodyParagraphs.isArray() || bodyParagraphs.isEmpty()) {
            return claims;
        }

        Map<String, Integer> submittedCounts = new LinkedHashMap<>();
        boolean allParagraphPointersAreFinal = true;
        boolean hasContainerPointer = false;
        boolean hasLeafPointer = false;
        for (GeneratedClaim claim : claims) {
            if (claim == null) {
                continue;
            }
            for (String contentPath : safe(claim.getContentPaths())) {
                if (contentPath == null
                        || (!contentPath.equals(BODY_PARAGRAPHS_PATH)
                                && !contentPath.startsWith(
                                        BODY_PARAGRAPHS_PATH + "/"))) {
                    continue;
                }
                submittedCounts.merge(contentPath, 1, Integer::sum);
                allParagraphPointersAreFinal =
                        allParagraphPointersAreFinal
                                && isFinalContent(
                                        claim.getDisposition());
                hasContainerPointer =
                        hasContainerPointer
                                || contentPath.equals(
                                        BODY_PARAGRAPHS_PATH);
                hasLeafPointer =
                        hasLeafPointer
                                || !contentPath.equals(
                                        BODY_PARAGRAPHS_PATH);
            }
        }

        require(
                !hasContainerPointer || !hasLeafPointer,
                "$.claims",
                "cover-letter body paragraph claims mix container and leaf paths");
        if (!allParagraphPointersAreFinal) {
            return claims;
        }

        Map<String, Integer> expectedOneBasedCounts =
                new LinkedHashMap<>();
        Map<String, String> oneBasedToZeroBased =
                new LinkedHashMap<>();
        for (int index = 0;
                index < bodyParagraphs.size();
                index++) {
            String oneBased =
                    BODY_PARAGRAPHS_PATH + "/" + (index + 1);
            expectedOneBasedCounts.put(oneBased, 1);
            oneBasedToZeroBased.put(
                    oneBased,
                    BODY_PARAGRAPHS_PATH + "/" + index);
        }
        if (!submittedCounts.equals(expectedOneBasedCounts)) {
            return claims;
        }

        List<GeneratedClaim> normalized =
                new ArrayList<>(claims.size());
        for (GeneratedClaim claim : claims) {
            if (claim == null
                    || !isFinalContent(
                            claim.getDisposition())) {
                normalized.add(claim);
                continue;
            }
            List<String> contentPaths =
                    safe(claim.getContentPaths());
            List<String> shiftedPaths =
                    new ArrayList<>(contentPaths.size());
            boolean shifted = false;
            for (String contentPath : contentPaths) {
                String shiftedPath =
                        oneBasedToZeroBased.get(contentPath);
                shiftedPaths.add(
                        shiftedPath == null
                                ? contentPath
                                : shiftedPath);
                shifted = shifted || shiftedPath != null;
            }
            if (!shifted) {
                normalized.add(claim);
                continue;
            }
            GeneratedClaim copy =
                    copyWithClaimId(
                            claim,
                            claim.getClaimId());
            copy.setContentPaths(List.copyOf(shiftedPaths));
            normalized.add(copy);
        }
        return List.copyOf(normalized);
    }

    List<GeneratedClaim> normalizeCompleteOneBasedNestedTextArrayPaths(
            JsonNode output,
            List<GeneratedClaim> claims,
            boolean versionedEvidence
    ) {
        if (!versionedEvidence) {
            return claims;
        }
        List<GeneratedClaim> normalized = claims;
        JsonNode projects = output.at("/cv/projects");
        for (int index = 0; index < projects.size(); index++) {
            normalized = normalizeCompleteOneBasedTextArrayPaths(
                    output,
                    normalized,
                    "/cv/projects/" + index + "/highlights");
        }
        JsonNode workHistory = output.at("/cv/workHistory");
        for (int index = 0; index < workHistory.size(); index++) {
            normalized = normalizeCompleteOneBasedTextArrayPaths(
                    output,
                    normalized,
                    "/cv/workHistory/" + index + "/responsibilities");
        }
        return normalized;
    }

    private List<GeneratedClaim> normalizeCompleteOneBasedTextArrayPaths(
            JsonNode output,
            List<GeneratedClaim> claims,
            String arrayPath
    ) {
        JsonNode values = output.at(arrayPath);
        if (!values.isArray() || values.isEmpty()) {
            return claims;
        }

        Map<String, Integer> submittedCounts = new LinkedHashMap<>();
        boolean allPointersAreFinal = true;
        for (GeneratedClaim claim : claims) {
            if (claim == null) {
                continue;
            }
            for (String contentPath : safe(claim.getContentPaths())) {
                if (contentPath == null
                        || !contentPath.startsWith(arrayPath + "/")) {
                    continue;
                }
                submittedCounts.merge(contentPath, 1, Integer::sum);
                allPointersAreFinal = allPointersAreFinal
                        && isFinalContent(claim.getDisposition());
            }
        }
        if (!allPointersAreFinal) {
            return claims;
        }

        Map<String, String> oneBasedToZeroBased =
                new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            String oneBased = arrayPath + "/" + (index + 1);
            oneBasedToZeroBased.put(
                    oneBased,
                    arrayPath + "/" + index);
        }
        if (!submittedCounts.keySet().equals(
                oneBasedToZeroBased.keySet())) {
            return claims;
        }

        List<GeneratedClaim> normalized =
                new ArrayList<>(claims.size());
        for (GeneratedClaim claim : claims) {
            if (claim == null || !isFinalContent(claim.getDisposition())) {
                normalized.add(claim);
                continue;
            }
            List<String> contentPaths = safe(claim.getContentPaths());
            List<String> shiftedPaths =
                    new ArrayList<>(contentPaths.size());
            boolean shifted = false;
            for (String contentPath : contentPaths) {
                String shiftedPath = oneBasedToZeroBased.get(contentPath);
                shiftedPaths.add(
                        shiftedPath == null ? contentPath : shiftedPath);
                shifted = shifted || shiftedPath != null;
            }
            if (!shifted) {
                normalized.add(claim);
                continue;
            }
            GeneratedClaim copy = copyWithClaimId(
                    claim,
                    claim.getClaimId());
            copy.setContentPaths(List.copyOf(shiftedPaths));
            normalized.add(copy);
        }
        return List.copyOf(normalized);
    }

    private Set<String> claimContentTopologyPaths(
            JsonNode output
    ) {
        Set<String> paths = new LinkedHashSet<>();
        for (String path : List.of(
                "/cv/title",
                "/cv/targetRole",
                "/cv/personalSummary",
                "/coverLetter/title",
                "/coverLetter/jobTitle",
                "/coverLetter/companyName",
                "/coverLetter/openingParagraph",
                "/coverLetter/closingParagraph")) {
            addExistingPath(paths, output, path);
        }
        addObjectArrayTopology(
                paths,
                output,
                "/cv/coreSkills",
                List.of("name", "evidence"));
        addObjectArrayTopology(
                paths,
                output,
                "/cv/projects",
                List.of(
                        "title",
                        "role",
                        "context",
                        "startDate",
                        "endDate",
                        "description"));
        addNestedTextArrayTopology(
                paths,
                output,
                "/cv/projects",
                "highlights");
        addObjectArrayTopology(
                paths,
                output,
                "/cv/qualifications",
                List.of(
                        "qualificationName",
                        "issuingBody",
                        "status",
                        "grade",
                        "dateAchieved",
                        "expectedCompletion"));
        addObjectArrayTopology(
                paths,
                output,
                "/cv/workHistory",
                List.of(
                        "jobTitle",
                        "employer",
                        "startDate",
                        "endDate",
                        "tailoredDescription"));
        addNestedTextArrayTopology(
                paths,
                output,
                "/cv/workHistory",
                "responsibilities");
        addTextArrayTopology(
                paths,
                output,
                "/coverLetter/bodyParagraphs");
        return Set.copyOf(paths);
    }

    private void addObjectArrayTopology(
            Set<String> paths,
            JsonNode output,
            String arrayPath,
            List<String> fields
    ) {
        addExistingPath(paths, output, arrayPath);
        JsonNode array = output.at(arrayPath);
        for (int index = 0; index < array.size(); index++) {
            String itemPath = arrayPath + "/" + index;
            addExistingPath(paths, output, itemPath);
            for (String field : fields) {
                addExistingPath(
                        paths,
                        output,
                        itemPath + "/" + field);
            }
        }
    }

    private void addNestedTextArrayTopology(
            Set<String> paths,
            JsonNode output,
            String parentArrayPath,
            String field
    ) {
        JsonNode parents = output.at(parentArrayPath);
        for (int index = 0;
                index < parents.size();
                index++) {
            addTextArrayTopology(
                    paths,
                    output,
                    parentArrayPath
                            + "/"
                            + index
                            + "/"
                            + field);
        }
    }

    private void addTextArrayTopology(
            Set<String> paths,
            JsonNode output,
            String arrayPath
    ) {
        addExistingPath(paths, output, arrayPath);
        JsonNode array = output.at(arrayPath);
        for (int index = 0; index < array.size(); index++) {
            addExistingPath(
                    paths,
                    output,
                    arrayPath + "/" + index);
        }
    }

    private void addExistingPath(
            Set<String> paths,
            JsonNode output,
            String path
    ) {
        if (!output.at(path).isMissingNode()) {
            paths.add(path);
        }
    }

    private String boundedStructuralPath(String path) {
        if (path == null) {
            return "<null>";
        }
        int limit = 200;
        return path.length() <= limit
                ? path
                : path.substring(0, limit) + "...";
    }

    private void canonicalizeCitedAtomicContent(
            JsonNode output,
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        for (GeneratedClaim claim : claims) {
            if (claim == null
                    || !isFinalContent(claim.getDisposition())) {
                continue;
            }
            for (String contentPath : safe(claim.getContentPaths())) {
                Predicate<ApprovedEvidenceRecord> atomicEvidence =
                        atomicEvidenceFor(contentPath);
                EvidencePurpose purpose =
                        contentPath.startsWith("/cv/")
                                ? EvidencePurpose.CV
                                : contentPath.startsWith("/coverLetter/")
                                        ? EvidencePurpose.COVER_LETTER
                                        : null;
                JsonNode current = output.at(contentPath);
                if (atomicEvidence == null
                        || purpose == null
                        || !current.isTextual()
                        || !StringUtils.hasText(current.textValue())) {
                    continue;
                }
                Map<String, ApprovedEvidenceRecord> uniqueFacts =
                        new LinkedHashMap<>();
                for (String evidenceId : safe(
                        claim.getEvidenceIds())) {
                    for (ApprovedEvidenceRecord record :
                            evidenceById.getOrDefault(
                                    evidenceId, List.of())) {
                        if (record.purpose().supports(purpose)
                                && atomicEvidence.test(record)) {
                            uniqueFacts.putIfAbsent(
                                    normalise(record.value()),
                                    record);
                        }
                    }
                }
                if (uniqueFacts.isEmpty()) {
                    evidenceById.values().stream()
                            .flatMap(List::stream)
                            .filter(record ->
                                    record.purpose()
                                            .supports(purpose))
                            .filter(atomicEvidence)
                            .forEach(record ->
                                    uniqueFacts.putIfAbsent(
                                            normalise(record.value()),
                                            record));
                }
                if (uniqueFacts.values().stream().anyMatch(
                        record -> equalText(
                                current.textValue(),
                                record.value()))) {
                    continue;
                }
                if (uniqueFacts.size() != 1
                        && mayBeEmptyWhenUnsupported(contentPath)) {
                    replaceText(output, contentPath, "");
                    continue;
                }
                if (uniqueFacts.size() == 1) {
                    replaceText(
                            output,
                            contentPath,
                            uniqueFacts.values()
                                    .iterator()
                                    .next()
                                    .value());
                }
            }
        }
    }

    private boolean mayBeEmptyWhenUnsupported(String contentPath) {
        return contentPath.matches(
                        "/cv/qualifications/\\d+/"
                                + "(issuingBody|status|grade|dateAchieved|expectedCompletion)")
                || contentPath.matches("/cv/workHistory/\\d+/endDate")
                || contentPath.matches(
                        "/cv/projects/\\d+/(title|role|context|startDate|endDate)");
    }

    private void canonicalizeDocumentIdentity(
            JsonNode output,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        String jobTitle = exactEvidenceValue(evidenceById, "JOB.TITLE");
        String companyName = canonicalHiringOrganisation(evidenceById);
        String applicationContact = exactEvidenceValue(
                evidenceById,
                "JOB.APPLICATION_CONTACT");
        if (StringUtils.hasText(jobTitle)) {
            replaceText(output, "/cv/title", jobTitle + " CV");
            replaceText(output, "/cv/targetRole", jobTitle);
            replaceText(output, "/coverLetter/title", jobTitle + " Cover Letter");
            replaceText(output, "/coverLetter/jobTitle", jobTitle);
        }
        if (StringUtils.hasText(companyName)) {
            replaceText(output, "/coverLetter/companyName", companyName);
        }
        boolean namedContact = StringUtils.hasText(applicationContact);
        replaceText(
                output,
                "/coverLetter/greeting",
                namedContact
                        ? "Dear " + applicationContact
                        : "Dear Hiring Manager");
        replaceText(
                output,
                "/coverLetter/signOff",
                namedContact ? "Yours sincerely" : "Yours faithfully");
    }

    private String canonicalHiringOrganisation(
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        String hiringOrganisation = exactEvidenceValue(
                evidenceById,
                "JOB.HIRING_ORGANISATION");
        if (StringUtils.hasText(hiringOrganisation)) {
            return hiringOrganisation;
        }
        String advertiserType = exactEvidenceValue(
                evidenceById,
                "JOB.ADVERTISER_TYPE");
        if ("RECRUITER".equals(advertiserType)) {
            return "the client organisation";
        }
        return exactEvidenceValue(evidenceById, "JOB.COMPANY");
    }

    private List<GeneratedClaim> ensureCanonicalTitleCoverage(
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            String titlePath,
            String preferredIdentityPath,
            EvidencePurpose purpose
    ) {
        boolean alreadyCovered = claims.stream()
                .filter(java.util.Objects::nonNull)
                .filter(claim -> isFinalContent(claim.getDisposition()))
                .anyMatch(claim -> safe(claim.getContentPaths())
                                .contains(titlePath)
                        && safe(claim.getEvidenceIds()).contains(
                                JOB_TITLE_EVIDENCE_ID));
        if (alreadyCovered) {
            return claims;
        }
        boolean canonicalEvidenceAvailable = evidenceById
                .getOrDefault(JOB_TITLE_EVIDENCE_ID, List.of())
                .stream()
                .anyMatch(record -> record.purpose().supports(purpose));
        if (!canonicalEvidenceAvailable) {
            return claims;
        }

        int compatibleIndex = -1;
        for (int index = 0; index < claims.size(); index++) {
            GeneratedClaim claim = claims.get(index);
            if (claim == null
                    || !isFinalContent(claim.getDisposition())
                    || claim.getClaimId() == null
                    || claim.getClaimId().startsWith("CLAIM-9")
                    || !safe(claim.getEvidenceIds()).contains(
                            JOB_TITLE_EVIDENCE_ID)
                    || safe(claim.getContentPaths()).size()
                            >= MAX_CLAIM_REFERENCES
                    || !safe(claim.getContentPaths()).stream()
                            .allMatch(path -> purpose == EvidencePurpose.CV
                                    ? path != null
                                            && path.startsWith("/cv/")
                                    : path != null
                                            && path.startsWith(
                                                    "/coverLetter/"))) {
                continue;
            }
            if (!safe(claim.getContentPaths()).contains(
                    preferredIdentityPath)) {
                continue;
            }
            compatibleIndex = index;
            break;
        }

        List<GeneratedClaim> normalized = new ArrayList<>(claims);
        if (compatibleIndex >= 0) {
            GeneratedClaim source = claims.get(compatibleIndex);
            GeneratedClaim copy = copyWithClaimId(
                    source,
                    source.getClaimId());
            List<String> paths = new ArrayList<>(
                    safe(source.getContentPaths()));
            paths.add(titlePath);
            copy.setContentPaths(List.copyOf(paths));
            normalized.set(compatibleIndex, copy);
            return List.copyOf(normalized);
        }

        Set<String> usedClaimIds = claims.stream()
                .filter(java.util.Objects::nonNull)
                .map(GeneratedClaim::getClaimId)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());
        int nextClaimNumber = 3000;
        String claimId;
        do {
            claimId = "CLAIM-" + nextClaimNumber++;
        } while (usedClaimIds.contains(claimId));
        GeneratedClaim generated = new GeneratedClaim();
        generated.setClaimId(claimId);
        generated.setDisposition(ClaimDisposition.SUPPORTED);
        generated.setEvidenceIds(List.of(JOB_TITLE_EVIDENCE_ID));
        generated.setContentPaths(List.of(titlePath));
        generated.setReviewText("");
        normalized.add(generated);
        return List.copyOf(normalized);
    }

    private String exactEvidenceValue(
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            String evidenceId
    ) {
        return evidenceById.getOrDefault(evidenceId, List.of()).stream()
                .map(ApprovedEvidenceRecord::value)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("");
    }

    private void canonicalizeOptionalAtomicContent(
            JsonNode output,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        for (String contentPath : claimBearingPaths(output)) {
            if (!mayBeEmptyWhenUnsupported(contentPath)) {
                continue;
            }
            JsonNode current = output.at(contentPath);
            Predicate<ApprovedEvidenceRecord> atomicEvidence =
                    atomicEvidenceFor(contentPath);
            if (!current.isTextual()
                    || !StringUtils.hasText(current.textValue())
                    || atomicEvidence == null) {
                continue;
            }
            Map<String, ApprovedEvidenceRecord> uniqueFacts =
                    new LinkedHashMap<>();
            evidenceById.values().stream()
                    .flatMap(List::stream)
                    .filter(record ->
                            record.purpose().supports(EvidencePurpose.CV))
                    .filter(record -> candidateEvidence(record.source()))
                    .filter(atomicEvidence)
                    .forEach(record -> uniqueFacts.putIfAbsent(
                            normalise(record.value()),
                            record));
            if (uniqueFacts.values().stream().anyMatch(
                    record -> equalText(
                            current.textValue(),
                            record.value()))) {
                continue;
            }
            replaceText(
                    output,
                    contentPath,
                    uniqueFacts.size() == 1
                            ? uniqueFacts.values()
                                    .iterator()
                                    .next()
                                    .value()
                            : "");
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

    private void canonicalizeUnclaimedProjectDescriptions(
            JsonNode output,
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        Set<String> currentPaths = claimBearingPaths(output);
        List<GeneratedClaim> expandedClaims = expandContainerContentPaths(
                        claims,
                        currentPaths);
        Set<String> claimedPaths = expandedClaims.stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(claim -> safe(claim.getContentPaths()).stream())
                .collect(java.util.stream.Collectors.toSet());
        JsonNode projects = output.at("/cv/projects");
        for (int index = 0; index < projects.size(); index++) {
            String descriptionPath =
                    "/cv/projects/" + index + "/description";
            if (currentPaths.contains(descriptionPath)
                    && !claimedPaths.contains(descriptionPath)) {
                ApprovedEvidenceRecord description =
                        uniqueProjectDescriptionEvidence(
                                output,
                                expandedClaims,
                                evidenceById,
                                index);
                if (description != null) {
                    replaceText(
                            output,
                            descriptionPath,
                            description.value());
                }
            }
        }
    }

    private void clearUnclaimedTailoredDescriptions(
            JsonNode output,
            List<GeneratedClaim> claims
    ) {
        Set<String> claimedPaths = claims.stream()
                .filter(java.util.Objects::nonNull)
                .filter(claim -> isFinalContent(claim.getDisposition()))
                .flatMap(claim -> safe(claim.getContentPaths()).stream())
                .collect(java.util.stream.Collectors.toSet());
        JsonNode workHistory = output.at("/cv/workHistory");
        for (int index = 0; index < workHistory.size(); index++) {
            String descriptionPath =
                    "/cv/workHistory/" + index + "/tailoredDescription";
            JsonNode description = output.at(descriptionPath);
            if (description.isTextual()
                    && StringUtils.hasText(description.textValue())
                    && !claimedPaths.contains(descriptionPath)) {
                replaceText(output, descriptionPath, "");
            }
        }
    }

    private void canonicalizeUnclaimedApplicationBookends(
            JsonNode output,
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            boolean versionedEvidence
    ) {
        if (!versionedEvidence) {
            return;
        }
        List<ApprovedEvidenceRecord> canonicalEvidence =
                canonicalApplicationEvidence(evidenceById);
        if (canonicalEvidence.isEmpty()) {
            return;
        }
        Set<String> currentPaths = claimBearingPaths(output);
        Set<String> claimedPaths = expandContainerContentPaths(
                        claims,
                        currentPaths)
                .stream()
                .filter(java.util.Objects::nonNull)
                .filter(claim ->
                        isFinalContent(claim.getDisposition()))
                .flatMap(claim ->
                        safe(claim.getContentPaths()).stream())
                .collect(java.util.stream.Collectors.toSet());
        for (String contentPath : List.of(
                OPENING_PARAGRAPH_PATH,
                CLOSING_PARAGRAPH_PATH)) {
            if (currentPaths.contains(contentPath)
                    && !claimedPaths.contains(contentPath)) {
                replaceText(
                        output,
                        contentPath,
                        canonicalApplicationBookend(contentPath));
            }
        }
    }

    private List<ApprovedEvidenceRecord> canonicalApplicationEvidence(
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        ApprovedEvidenceRecord intent = uniqueEvidence(
                evidenceById,
                GENERATION_INTENT_EVIDENCE_ID,
                EvidenceSource.REQUEST);
        ApprovedEvidenceRecord jobTitle = uniqueEvidence(
                evidenceById,
                JOB_TITLE_EVIDENCE_ID,
                EvidenceSource.JOB);
        ApprovedEvidenceRecord company = uniqueEvidence(
                evidenceById,
                JOB_COMPANY_EVIDENCE_ID,
                EvidenceSource.JOB);
        if (intent == null
                || jobTitle == null
                || company == null) {
            return List.of();
        }
        return List.of(intent, jobTitle, company);
    }

    private ApprovedEvidenceRecord uniqueEvidence(
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            String evidenceId,
            EvidenceSource source
    ) {
        List<ApprovedEvidenceRecord> compatible =
                evidenceById.getOrDefault(evidenceId, List.of())
                        .stream()
                        .filter(record -> record.source() == source)
                        .filter(record -> record.purpose()
                                .supports(EvidencePurpose.COVER_LETTER))
                        .filter(record ->
                                StringUtils.hasText(record.value()))
                        .toList();
        return compatible.size() == 1
                ? compatible.get(0)
                : null;
    }

    private String canonicalApplicationBookend(
            String contentPath
    ) {
        if (OPENING_PARAGRAPH_PATH.equals(contentPath)) {
            return "Please consider my application for this role.";
        }
        if (CLOSING_PARAGRAPH_PATH.equals(contentPath)) {
            return "Thank you for considering my application.";
        }
        return "";
    }

    private boolean isCanonicalApplicationBookend(
            JsonNode output,
            String contentPath,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById
    ) {
        if (!OPENING_PARAGRAPH_PATH.equals(contentPath)
                && !CLOSING_PARAGRAPH_PATH.equals(contentPath)) {
            return false;
        }
        JsonNode value = output.at(contentPath);
        List<ApprovedEvidenceRecord> canonicalEvidence =
                canonicalApplicationEvidence(evidenceById);
        return value.isTextual()
                && !canonicalEvidence.isEmpty()
                && isCanonicalApplicationBookendText(
                        contentPath,
                        value.textValue());
    }

    private boolean isCanonicalApplicationBookendText(
            String contentPath,
            String content
    ) {
        return content != null
                && (OPENING_PARAGRAPH_PATH.equals(contentPath)
                        || CLOSING_PARAGRAPH_PATH.equals(
                                contentPath))
                && content.equals(
                        canonicalApplicationBookend(contentPath));
    }

    private ApprovedEvidenceRecord uniqueProjectDescriptionEvidence(
            JsonNode output,
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            int projectIndex
    ) {
        String titleSelection = uniqueProjectSelection(
                output,
                claims,
                evidenceById,
                projectIndex);
        if (!StringUtils.hasText(titleSelection)) {
            return null;
        }
        List<ApprovedEvidenceRecord> descriptions =
                evidenceById.values().stream()
                        .flatMap(List::stream)
                        .filter(record ->
                                record.source()
                                        == EvidenceSource.EVIDENCE_SNAPSHOT)
                        .filter(record ->
                                record.purpose()
                                        .supports(EvidencePurpose.CV))
                        .filter(record ->
                                "PROJECT".equals(record.category()))
                        .filter(record ->
                                "DESCRIPTION".equals(record.factType()))
                        .filter(record ->
                                titleSelection.equals(
                                        evidenceSelection(record)))
                        .toList();
        return descriptions.size() == 1
                ? descriptions.get(0)
                : null;
    }

    private String uniqueProjectSelection(
            JsonNode output,
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            int projectIndex
    ) {
        String titlePath = "/cv/projects/" + projectIndex + "/title";
        JsonNode title = output.at(titlePath);
        if (!title.isTextual()
                || !StringUtils.hasText(title.textValue())) {
            return "";
        }
        Set<String> titleSelections = claims.stream()
                .filter(java.util.Objects::nonNull)
                .filter(claim ->
                        isFinalContent(claim.getDisposition()))
                .filter(claim ->
                        safe(claim.getContentPaths())
                                .contains(titlePath))
                .flatMap(claim ->
                        safe(claim.getEvidenceIds()).stream())
                .flatMap(evidenceId ->
                        evidenceById
                                .getOrDefault(evidenceId, List.of())
                                .stream())
                .filter(record ->
                        record.source()
                                == EvidenceSource.EVIDENCE_SNAPSHOT)
                .filter(record ->
                        record.purpose()
                                .supports(EvidencePurpose.CV))
                .filter(record ->
                        "PROJECT".equals(record.category()))
                .filter(record ->
                        "HEADING".equals(record.factType()))
                .filter(record ->
                        equalText(
                                title.textValue(),
                                record.value()))
                .map(this::evidenceSelection)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());
        return titleSelections.size() == 1
                ? titleSelections.iterator().next()
                : "";
    }

    private String evidenceSelection(
            ApprovedEvidenceRecord record
    ) {
        if (record == null
                || !StringUtils.hasText(record.sourcePath())) {
            return "";
        }
        int facts = record.sourcePath().indexOf("/facts/");
        return facts < 0
                ? ""
                : record.sourcePath().substring(0, facts);
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
                owner.setEvidenceIds(mergeBoundedEvidenceReferences(
                        safe(owner.getEvidenceIds()),
                        safe(claim.getEvidenceIds())));
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
            Set<String> expectedPaths,
            boolean versionedEvidence,
            Map<String, String> projectedCoreSkillEvidenceByPath
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
            List<String> matchingEvidenceIds;
            String projectedCoreSkillEvidenceId =
                    projectedCoreSkillEvidenceByPath.get(contentPath);
            if (projectedCoreSkillEvidenceId != null) {
                matchingEvidenceIds = List.of(
                        projectedCoreSkillEvidenceId);
            } else if (contentPath.equals("/coverLetter/companyName")) {
                matchingEvidenceIds = evidenceById.entrySet().stream()
                        .filter(entry -> entry.getValue().stream()
                                .anyMatch(record -> record.purpose()
                                        .supports(purpose)))
                        .filter(entry -> canonicalCompanyEvidenceMatches(
                                value.asText(),
                                entry.getValue()))
                        .map(Map.Entry::getKey)
                        .sorted()
                        .limit(1)
                        .toList();
            } else if (versionedEvidence
                    && isCanonicalApplicationBookend(
                            output,
                            contentPath,
                            evidenceById)) {
                matchingEvidenceIds = List.of(
                        GENERATION_INTENT_EVIDENCE_ID,
                        JOB_TITLE_EVIDENCE_ID,
                        JOB_COMPANY_EVIDENCE_ID);
            } else if (contentPath.matches(
                    "/cv/projects/\\d+/description")) {
                int projectIndex = Integer.parseInt(
                        contentPath.split("/")[3]);
                ApprovedEvidenceRecord description =
                        uniqueProjectDescriptionEvidence(
                                output,
                                claims,
                                evidenceById,
                                projectIndex);
                matchingEvidenceIds =
                        description != null
                                        && equalText(
                                                value.asText(),
                                                description.value())
                                ? List.of(description.evidenceId())
                                : List.of();
            } else {
                matchingEvidenceIds =
                        evidenceById.entrySet().stream()
                                .filter(entry ->
                                        entry.getValue().stream()
                                                .anyMatch(record ->
                                                        record.purpose()
                                                                .supports(purpose)
                                                                && (!requiresConfirmedCandidateEvidence(
                                                                        contentPath)
                                                                        || candidateEvidence(
                                                                                record.source()))
                                                                && (atomicEvidence
                                                                                == null
                                                                        || atomicEvidence
                                                                                .test(record))
                                                                && equalText(
                                                                        value.asText(),
                                                                        record.value())))
                                .map(Map.Entry::getKey)
                                .toList();
            }
            if (matchingEvidenceIds.isEmpty()) {
                matchingEvidenceIds = contextualNarrativeEvidenceIds(
                        claims,
                        contentPath,
                        value,
                        evidenceById,
                        purpose);
            }
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

    private List<String> contextualNarrativeEvidenceIds(
            List<GeneratedClaim> claims,
            String contentPath,
            JsonNode contentValue,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            EvidencePurpose purpose
    ) {
        List<GeneratedClaim> contextualClaims;
        Matcher workResponsibility = Pattern.compile(
                "^/cv/workHistory/(\\d+)/responsibilities/\\d+$")
                .matcher(contentPath);
        if (workResponsibility.matches()) {
            String contextPrefix = "/cv/workHistory/"
                    + workResponsibility.group(1)
                    + "/";
            contextualClaims = finalClaimsWithin(
                    claims,
                    contextPrefix);
        } else if (contentPath.matches(
                "^/coverLetter/bodyParagraphs/\\d+$")) {
            contextualClaims = finalClaimsWithin(
                    claims,
                    "/coverLetter/bodyParagraphs/");
            if (contextualClaims.isEmpty()
                    && contentValue.isTextual()) {
                contextualClaims = claims.stream()
                        .filter(java.util.Objects::nonNull)
                        .filter(claim -> isFinalContent(
                                claim.getDisposition()))
                        .filter(this::isStructuredCvSiblingClaim)
                        .filter(claim -> hasNarrativeAnchor(
                                claim,
                                contentValue.textValue(),
                                evidenceById,
                                purpose))
                        .toList();
            }
        } else {
            return List.of();
        }
        return contextualClaims.stream()
                .flatMap(claim -> safe(claim.getEvidenceIds()).stream())
                .filter(evidenceById::containsKey)
                .filter(evidenceId -> evidenceById.get(evidenceId).stream()
                        .anyMatch(record -> record.purpose().supports(purpose)))
                .distinct()
                .limit(MAX_CLAIM_REFERENCES)
                .toList();
    }

    private List<GeneratedClaim> finalClaimsWithin(
            List<GeneratedClaim> claims,
            String contextPrefix
    ) {
        return claims.stream()
                .filter(java.util.Objects::nonNull)
                .filter(claim -> isFinalContent(claim.getDisposition()))
                .filter(claim -> safe(claim.getContentPaths()).stream()
                        .anyMatch(path -> path.startsWith(contextPrefix)))
                .toList();
    }

    private boolean isStructuredCvSiblingClaim(GeneratedClaim claim) {
        return safe(claim.getContentPaths()).stream()
                .anyMatch(path -> path.startsWith("/cv/projects/")
                        || path.startsWith("/cv/workHistory/")
                        || path.startsWith("/cv/qualifications/")
                        || path.startsWith("/cv/coreSkills/"));
    }

    private boolean hasNarrativeAnchor(
            GeneratedClaim claim,
            String narrative,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            EvidencePurpose purpose
    ) {
        return safe(claim.getEvidenceIds()).stream()
                .flatMap(evidenceId -> evidenceById
                        .getOrDefault(evidenceId, List.of())
                        .stream())
                .filter(record -> record.purpose().supports(purpose))
                .filter(record -> factType(
                        record,
                        "HEADING",
                        "PROJECT_TITLE",
                        "ROLE_TITLE",
                        "QUALIFICATION_TITLE",
                        "QUALIFICATION_NAME",
                        "DECLARED_SKILL",
                        "DEMONSTRATED_SKILL"))
                .anyMatch(record -> containsWholeEvidenceValue(
                        narrative,
                        record.value()));
    }

    private boolean containsWholeEvidenceValue(
            String narrative,
            String evidenceValue
    ) {
        String normalizedNarrative = normalise(narrative);
        String normalizedEvidence = normalise(evidenceValue);
        if (normalizedEvidence.length() < 3) {
            return false;
        }
        return Pattern.compile(
                        "(?<![\\p{L}\\p{N}])"
                                + Pattern.quote(normalizedEvidence)
                                + "(?![\\p{L}\\p{N}])")
                .matcher(normalizedNarrative)
                .find();
    }

    private List<GeneratedClaim> enrichAtomicEvidenceReferences(
            JsonNode output,
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            boolean projectedCoreSkills,
            boolean preserveCanonicalTitleProvenance
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
            LinkedHashSet<String> requiredAtomicEvidenceIds =
                    new LinkedHashSet<>();
            LinkedHashSet<String> requiredSpecificEvidenceIds =
                    new LinkedHashSet<>();
            LinkedHashSet<String> supplementalEvidenceIds =
                    new LinkedHashSet<>();
            for (String contentPath : claim.getContentPaths()) {
                if (preserveCanonicalTitleProvenance
                        && isCanonicalDocumentTitlePath(contentPath)) {
                    continue;
                }
                Predicate<ApprovedEvidenceRecord> atomicEvidence =
                        atomicEvidenceFor(contentPath);
                boolean projectedCoreSkill = projectedCoreSkills
                        && contentPath.matches(
                                "/cv/coreSkills/\\d+/name");
                JsonNode value = output.at(contentPath);
                if (!value.isTextual()
                        || !StringUtils.hasText(value.textValue())) {
                    continue;
                }
                evidenceById.forEach((evidenceId, candidates) -> {
                    boolean exactApprovedFact =
                            !projectedCoreSkill
                                    && atomicEvidence != null
                                    && candidates.stream()
                                            .anyMatch(record ->
                                                    record.purpose().supports(purpose)
                                                            && atomicEvidence.test(record)
                                                            && equalText(
                                                                    value.textValue(),
                                                                    record.value()));
                    boolean exactApprovedNarrative = !projectedCoreSkill
                            && candidates.stream()
                            .filter(record -> record.purpose().supports(purpose))
                            .filter(record -> !requiresConfirmedCandidateEvidence(
                                    contentPath)
                                    || candidateEvidence(record.source()))
                            .anyMatch(record -> equalText(
                                    value.textValue(),
                                    record.value()));
                    if (exactApprovedFact) {
                        requiredAtomicEvidenceIds.add(evidenceId);
                    }
                    if (exactApprovedNarrative) {
                        supplementalEvidenceIds.add(evidenceId);
                    }
                });
                if (!projectedCoreSkill) {
                    List<String> currentEvidenceIds =
                            mergeBoundedEvidenceReferences(
                                    mergeBoundedEvidenceReferences(
                                            List.copyOf(requiredAtomicEvidenceIds),
                                            requiredSpecificEvidenceIds),
                                    safe(claim.getEvidenceIds()));
                    requiredSpecificEvidenceIds.addAll(
                            requiredSpecificEvidenceReferences(
                                    contentPath,
                                    value.textValue(),
                                    currentEvidenceIds,
                                    evidenceById,
                                    purpose));
                }
            }
            GeneratedClaim copy = copyWithClaimId(
                    claim,
                    claim.getClaimId());
            List<String> requiredEvidenceIds =
                    mergeBoundedEvidenceReferences(
                            List.copyOf(requiredAtomicEvidenceIds),
                            requiredSpecificEvidenceIds);
            List<String> prioritizedEvidenceIds =
                    requiredEvidenceIds.isEmpty()
                            ? safe(claim.getEvidenceIds())
                            : mergeBoundedEvidenceReferences(
                                    requiredEvidenceIds,
                                    safe(claim.getEvidenceIds()));
            copy.setEvidenceIds(mergeBoundedEvidenceReferences(
                    prioritizedEvidenceIds,
                    supplementalEvidenceIds));
            normalized.add(copy);
        }
        return List.copyOf(normalized);
    }

    private List<String> requiredSpecificEvidenceReferences(
            String contentPath,
            String content,
            List<String> currentEvidenceIds,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            EvidencePurpose purpose
    ) {
        LinkedHashSet<String> required = new LinkedHashSet<>();
        StringBuilder evidenceText = new StringBuilder(
                evidenceText(
                        contentPath,
                        currentEvidenceIds,
                        evidenceById,
                        purpose));
        for (Pattern pattern : List.of(NUMERIC_CLAIM, SENSITIVE_CLAIM)) {
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String term = normalise(matcher.group());
                if (containsSupportedTerm(evidenceText.toString(), term)) {
                    continue;
                }
                SpecificEvidenceCandidate candidate = evidenceById.entrySet().stream()
                        .flatMap(entry -> entry.getValue().stream()
                                .filter(record -> record.purpose().supports(purpose))
                                .filter(record -> !requiresCandidateSpecificEvidence(
                                        contentPath)
                                        || candidateEvidence(record.source()))
                                .filter(record -> containsSupportedTerm(
                                        record.value(),
                                        term))
                                .map(record -> new SpecificEvidenceCandidate(
                                        entry.getKey(),
                                        record.value())))
                        .sorted(Comparator
                                .comparing((SpecificEvidenceCandidate value) ->
                                        !equalText(value.value(), term))
                                .thenComparingInt(value ->
                                        normalise(value.value()).length())
                                .thenComparing(SpecificEvidenceCandidate::evidenceId))
                        .findFirst()
                        .orElse(null);
                if (candidate != null && required.add(candidate.evidenceId())) {
                    evidenceText.append(' ').append(normalise(candidate.value()));
                }
            }
        }
        return List.copyOf(required);
    }

    private String evidenceText(
            String contentPath,
            List<String> evidenceIds,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            EvidencePurpose purpose
    ) {
        return safe(evidenceIds).stream()
                .flatMap(evidenceId -> evidenceById
                        .getOrDefault(evidenceId, List.of())
                .stream())
                .filter(record -> record.purpose().supports(purpose))
                .filter(record -> !requiresCandidateSpecificEvidence(
                        contentPath)
                        || candidateEvidence(record.source()))
                .map(ApprovedEvidenceRecord::value)
                .map(this::normalise)
                .reduce("", (left, right) -> left + " " + right);
    }

    private boolean requiresCandidateSpecificEvidence(String contentPath) {
        return contentPath.startsWith("/cv/")
                || requiresConfirmedCandidateEvidence(contentPath);
    }

    private boolean containsSupportedTerm(String value, String term) {
        return Pattern.compile(
                        "(?<![\\p{L}\\p{N}])"
                                + Pattern.quote(term)
                                + "(?![\\p{L}\\p{N}])")
                .matcher(normalise(value))
                .find();
    }

    private record SpecificEvidenceCandidate(
            String evidenceId,
            String value) {
    }

    List<String> mergeBoundedEvidenceReferences(
            List<String> primary,
            Iterable<String> supplemental
    ) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(safe(primary));
        if (merged.size() > MAX_CLAIM_REFERENCES) {
            return List.copyOf(merged);
        }
        for (String evidenceId : supplemental) {
            if (merged.contains(evidenceId)) {
                continue;
            }
            if (merged.size() == MAX_CLAIM_REFERENCES) {
                break;
            }
            merged.add(evidenceId);
        }
        return List.copyOf(merged);
    }

    private List<GeneratedClaim> isolateStructuredProjectClaims(
            JsonNode output,
            List<GeneratedClaim> claims,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            boolean versionedEvidence
    ) {
        if (!versionedEvidence) {
            return claims;
        }
        List<GeneratedClaim> normalized = new ArrayList<>(claims.size());
        for (GeneratedClaim claim : claims) {
            if (claim == null
                    || !isFinalContent(claim.getDisposition())) {
                normalized.add(claim);
                continue;
            }
            Map<Integer, List<String>> projectPaths =
                    new LinkedHashMap<>();
            List<String> otherPaths = new ArrayList<>();
            for (String contentPath : safe(claim.getContentPaths())) {
                Integer projectIndex =
                        projectIndex(contentPath);
                if (projectIndex != null) {
                    projectPaths.computeIfAbsent(
                                    projectIndex,
                                    ignored -> new ArrayList<>())
                            .add(contentPath);
                } else {
                    otherPaths.add(contentPath);
                }
            }
            if (projectPaths.isEmpty()) {
                normalized.add(claim);
                continue;
            }

            Map<Integer, String> selectionByProject =
                    new LinkedHashMap<>();
            for (Integer projectIndex : projectPaths.keySet()) {
                String selection = uniqueProjectSelection(
                        output,
                        claims,
                        evidenceById,
                        projectIndex);
                if (!StringUtils.hasText(selection)) {
                    selectionByProject.clear();
                    break;
                }
                selectionByProject.put(projectIndex, selection);
            }
            if (selectionByProject.size() != projectPaths.size()) {
                normalized.add(claim);
                continue;
            }
            boolean isolationRequired =
                    !otherPaths.isEmpty()
                            || projectPaths.size() > 1
                            || hasIrrelevantCvSnapshotEvidence(
                                    claim.getEvidenceIds(),
                                    evidenceById,
                                    selectionByProject.values()
                                            .iterator()
                                            .next());
            if (!isolationRequired) {
                normalized.add(claim);
                continue;
            }

            if (!otherPaths.isEmpty()) {
                GeneratedClaim nonProject = copyWithClaimId(
                        claim,
                        claim.getClaimId());
                nonProject.setContentPaths(List.copyOf(otherPaths));
                normalized.add(nonProject);
            }
            for (Map.Entry<Integer, List<String>> entry :
                    projectPaths.entrySet()) {
                String selection =
                        selectionByProject.get(entry.getKey());
                GeneratedClaim project = copyWithClaimId(
                        claim,
                        claim.getClaimId());
                project.setContentPaths(
                        List.copyOf(entry.getValue()));
                project.setEvidenceIds(
                        projectEvidenceIdsForSelection(
                                claim.getEvidenceIds(),
                                evidenceById,
                                selection));
                normalized.add(project);
            }
        }
        return List.copyOf(normalized);
    }

    private Integer projectIndex(String contentPath) {
        if (contentPath == null) {
            return null;
        }
        Matcher matcher =
                PROJECT_CONTENT_PATH.matcher(contentPath);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean hasIrrelevantCvSnapshotEvidence(
            List<String> evidenceIds,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            String selection
    ) {
        return safe(evidenceIds).stream()
                .flatMap(evidenceId ->
                        evidenceById
                                .getOrDefault(evidenceId, List.of())
                                .stream())
                .filter(record ->
                        record.source()
                                == EvidenceSource.EVIDENCE_SNAPSHOT)
                .filter(record ->
                        record.purpose()
                                .supports(EvidencePurpose.CV))
                .anyMatch(record ->
                        !"PROJECT".equals(record.category())
                                || !selection.equals(
                                        evidenceSelection(record)));
    }

    private List<String> projectEvidenceIdsForSelection(
            List<String> evidenceIds,
            Map<String, List<ApprovedEvidenceRecord>> evidenceById,
            String selection
    ) {
        return safe(evidenceIds).stream()
                .filter(evidenceId -> {
                    List<ApprovedEvidenceRecord> candidates =
                            evidenceById.get(evidenceId);
                    if (candidates == null) {
                        return true;
                    }
                    boolean hasCvSnapshot = candidates.stream()
                            .anyMatch(record ->
                                    record.source()
                                            == EvidenceSource
                                                    .EVIDENCE_SNAPSHOT
                                            && record.purpose()
                                                    .supports(
                                                            EvidencePurpose
                                                                    .CV));
                    return !hasCvSnapshot
                            || candidates.stream()
                                    .anyMatch(record ->
                                            record.source()
                                                    == EvidenceSource
                                                            .EVIDENCE_SNAPSHOT
                                                    && record.purpose()
                                                            .supports(
                                                                    EvidencePurpose
                                                                            .CV)
                                                    && "PROJECT".equals(
                                                            record.category())
                                                    && selection.equals(
                                                            evidenceSelection(
                                                                    record)));
                })
                .toList();
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
            boolean versionedEvidence,
            boolean enforceCanonicalApplicationBookends
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
        validateCanonicalBookendClaim(
                output,
                claim.getDisposition(),
                contentPaths,
                evidence,
                claimPath,
                enforceCanonicalApplicationBookends);
        for (String contentPath : contentPaths) {
            require(expectedPaths.contains(contentPath),
                    claimPath + ".contentPaths",
                    "content path is not an approved final claim path: "
                            + contentPath);
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

    private void validateCanonicalBookendClaim(
            JsonNode output,
            ClaimDisposition disposition,
            List<String> contentPaths,
            List<ApprovedEvidenceRecord> evidence,
            String claimPath,
            boolean enforceCanonicalApplicationBookends
    ) {
        if (!enforceCanonicalApplicationBookends) {
            return;
        }
        List<String> canonicalBookendPaths = contentPaths.stream()
                .filter(contentPath -> {
                    JsonNode value = output.at(contentPath);
                    return value.isTextual()
                            && isCanonicalApplicationBookendText(
                                    contentPath,
                                    value.textValue());
                })
                .toList();
        if (canonicalBookendPaths.isEmpty()) {
            return;
        }
        require(disposition == ClaimDisposition.SUPPORTED,
                claimPath + ".disposition",
                "canonical application bookend must be supported");
        require(canonicalBookendPaths.size() == 1
                        && contentPaths.size() == 1,
                claimPath + ".contentPaths",
                "canonical application bookend claim must be isolated");
        String contentPath = canonicalBookendPaths.get(0);
        require(isCanonicalApplicationBookend(
                        contentPath,
                        output.at(contentPath).textValue(),
                        evidence),
                claimPath + ".evidenceIds",
                "canonical application bookend must cite exactly canonical evidence");
    }

    private void validateEvidenceAlignment(
            String contentPath,
            String content,
            List<ApprovedEvidenceRecord> evidence,
            String claimPath,
            boolean versionedEvidence
    ) {
        boolean canonicalApplicationBookend =
                isCanonicalApplicationBookend(
                        contentPath,
                        content,
                        evidence);
        Predicate<ApprovedEvidenceRecord> atomicEvidence = atomicEvidenceFor(contentPath);
        if (contentPath.equals("/coverLetter/companyName")) {
            require(canonicalCompanyEvidenceMatches(content, evidence),
                    claimPath + ".evidenceIds",
                    "company name is not supported by canonical employer or recruiter evidence");
        } else if (atomicEvidence != null) {
            require(evidence.stream()
                            .filter(atomicEvidence)
                            .anyMatch(record -> equalText(content, record.value())),
                    claimPath + ".evidenceIds",
                    "atomic final claim is not an exact approved fact at "
                            + contentPath);
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
                        "candidate claim has no approved profile evidence at "
                                + contentPath);
            }
            if (versionedEvidence
                    && requiresConfirmedCandidateEvidence(contentPath)
                    && !isNonFactualMotivationalCoverLetterProse(
                            contentPath,
                            content,
                            evidence)
                    && !canonicalApplicationBookend) {
                require(evidence.stream().anyMatch(
                                record -> candidateEvidence(
                                        record.source())),
                        claimPath + ".evidenceIds",
                        "candidate claim has no confirmed claimant evidence at "
                                + contentPath);
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
        requireMatchesAreSupported(
                EMAIL_ADDRESS.matcher(content),
                evidenceText,
                claimPath,
                "contact claim is absent from approved evidence at "
                        + contentPath);
        requireMatchesAreSupported(
                UK_POSTCODE.matcher(content),
                evidenceText,
                claimPath,
                "location claim is absent from approved evidence at "
                        + contentPath);
        requireMatchesAreSupported(
                BIOGRAPHICAL_BOILERPLATE.matcher(content),
                evidenceText,
                claimPath,
                "identity or biographical claim is absent from approved evidence at "
                        + contentPath);
        if (!contentPath.startsWith("/coverLetter/")) {
            requireMatchesAreSupported(
                    MOTIVATIONAL_TONE.matcher(content),
                    evidenceText,
                    claimPath,
                    "motivational claim is absent from approved evidence at "
                            + contentPath);
        }
    }

    private boolean canonicalCompanyEvidenceMatches(
            String content,
            List<ApprovedEvidenceRecord> evidence
    ) {
        boolean exactOrganisation = evidence.stream()
                .filter(java.util.Objects::nonNull)
                .filter(record -> record.source() == EvidenceSource.JOB)
                .filter(record -> record.evidenceId().equals("JOB.COMPANY")
                        || record.evidenceId().equals(
                                "JOB.HIRING_ORGANISATION"))
                .anyMatch(record -> equalText(content, record.value()));
        if (exactOrganisation) {
            return true;
        }
        return equalText(content, "the client organisation")
                && evidence.stream()
                        .filter(java.util.Objects::nonNull)
                        .filter(record -> record.source() == EvidenceSource.JOB)
                        .filter(record -> record.evidenceId().equals(
                                "JOB.ADVERTISER_TYPE"))
                        .anyMatch(record -> equalText(
                                record.value(),
                                "RECRUITER"));
    }

    private boolean isCanonicalApplicationBookend(
            String contentPath,
            String content,
            List<ApprovedEvidenceRecord> evidence
    ) {
        if (!OPENING_PARAGRAPH_PATH.equals(contentPath)
                && !CLOSING_PARAGRAPH_PATH.equals(contentPath)) {
            return false;
        }
        Set<String> evidenceIds = evidence.stream()
                .filter(java.util.Objects::nonNull)
                .map(ApprovedEvidenceRecord::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
        if (!evidenceIds.equals(Set.of(
                GENERATION_INTENT_EVIDENCE_ID,
                JOB_TITLE_EVIDENCE_ID,
                JOB_COMPANY_EVIDENCE_ID))) {
            return false;
        }
        Map<String, ApprovedEvidenceRecord> canonicalEvidenceById =
                new LinkedHashMap<>();
        for (ApprovedEvidenceRecord record : evidence) {
            if (record == null) {
                continue;
            }
            if (GENERATION_INTENT_EVIDENCE_ID.equals(
                            record.evidenceId())
                    && record.source() == EvidenceSource.REQUEST) {
                canonicalEvidenceById.put(
                        record.evidenceId(),
                        record);
            } else if ((JOB_TITLE_EVIDENCE_ID.equals(
                                    record.evidenceId())
                            || JOB_COMPANY_EVIDENCE_ID.equals(
                                    record.evidenceId()))
                    && record.source() == EvidenceSource.JOB) {
                canonicalEvidenceById.put(
                        record.evidenceId(),
                        record);
            }
        }
        if (!canonicalEvidenceById.keySet().containsAll(Set.of(
                GENERATION_INTENT_EVIDENCE_ID,
                JOB_TITLE_EVIDENCE_ID,
                JOB_COMPANY_EVIDENCE_ID))) {
            return false;
        }
        return isCanonicalApplicationBookendText(
                contentPath,
                content);
    }

    private Predicate<ApprovedEvidenceRecord> atomicEvidenceFor(String path) {
        if (path.equals("/cv/targetRole")) {
            return record -> record.evidenceId().startsWith("PROFILE.TARGET_ROLE.")
                    || record.evidenceId().equals("JOB.TITLE");
        }
        if (path.matches("/cv/coreSkills/\\d+/name")) {
            return record -> record.evidenceId().startsWith("PROFILE.SKILL.")
                    || factType(
                            record,
                            "DEMONSTRATED_SKILL",
                            "DECLARED_SKILL");
        }
        if (path.matches("/cv/projects/\\d+/title")) {
            return record -> projectEvidence(record)
                    && factType(record, "HEADING");
        }
        if (path.matches("/cv/projects/\\d+/role")) {
            return record -> projectEvidence(record)
                    && factType(record, "PROJECT_ROLE", "ROLE_TITLE");
        }
        if (path.matches("/cv/projects/\\d+/context")) {
            return record -> projectEvidence(record)
                    && factType(record, "ORGANISATION_CONTEXT");
        }
        if (path.matches("/cv/projects/\\d+/startDate")) {
            return record -> projectEvidence(record)
                    && factType(record, "START_DATE");
        }
        if (path.matches("/cv/projects/\\d+/endDate")) {
            return record -> projectEvidence(record)
                    && factType(record, "END_DATE");
        }
        if (path.matches("/cv/qualifications/\\d+/qualificationName")) {
            return record -> suffix(".NAME").test(record)
                    || factType(
                            record,
                            "QUALIFICATION_TITLE",
                            "PROGRAMME_OR_SUBJECT");
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
                    || employmentEvidence(record)
                            && factType(record, "ROLE_TITLE", "HEADING");
        }
        if (path.matches("/cv/workHistory/\\d+/employer")) {
            return record -> employmentSuffix(".EMPLOYER").test(record)
                    || employmentEvidence(record)
                            && factType(
                                    record,
                                    "ORGANISATION",
                                    "ORGANISATION_CONTEXT");
        }
        if (path.matches("/cv/workHistory/\\d+/startDate")) {
            return record -> employmentSuffix(".START_DATE").test(record)
                    || employmentEvidence(record)
                            && factType(record, "START_DATE");
        }
        if (path.matches("/cv/workHistory/\\d+/endDate")) {
            return record -> employmentSuffix(".END_DATE").test(record)
                    || employmentEvidence(record)
                            && factType(record, "END_DATE");
        }
        if (path.equals("/coverLetter/jobTitle")) {
            return record -> record.evidenceId().equals("JOB.TITLE");
        }
        if (path.equals("/coverLetter/companyName")) {
            return record -> record.evidenceId().equals("JOB.COMPANY")
                    || record.evidenceId().equals(
                            "JOB.HIRING_ORGANISATION")
                    || record.evidenceId().equals(
                            "JOB.ADVERTISER_TYPE");
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

    private boolean isNonFactualMotivationalCoverLetterProse(
            String contentPath,
            String content,
            List<ApprovedEvidenceRecord> evidence
    ) {
        if (!contentPath.matches("^/coverLetter/bodyParagraphs/\\d+$")
                || !MOTIVATIONAL_TONE.matcher(content).find()
                || NUMERIC_CLAIM.matcher(content).find()
                || SENSITIVE_CLAIM.matcher(content).find()
                || CANDIDATE_FACTUAL_PROSE.matcher(content).find()) {
            return false;
        }
        return evidence.stream().anyMatch(
                        record -> record.source() == EvidenceSource.JOB)
                && evidence.stream().allMatch(
                        record -> record.source() == EvidenceSource.JOB
                                || record.source() == EvidenceSource.REQUEST);
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

    private boolean projectEvidence(ApprovedEvidenceRecord record) {
        return "PROJECT".equals(record.category());
    }

    private boolean employmentEvidence(ApprovedEvidenceRecord record) {
        return "EMPLOYMENT".equals(record.category());
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
        addObjectArrayText(paths, output, "/cv/projects", List.of(
                "title",
                "role",
                "context",
                "startDate",
                "endDate",
                "description"));
        addNestedTextArray(paths, output, "/cv/projects", "highlights");
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
