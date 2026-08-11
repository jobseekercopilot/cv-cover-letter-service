package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobseekercopilot.cvcoverletter.dto.ClaimDisposition;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedClaim;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedCoverLetter;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedCv;
import com.jobseekercopilot.cvcoverletter.dto.GenerationNotes;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class LlmResponseParser {

    static final String PARSER_VERSION = "3.6.3";
    static final String DEDICATED_PERSONAL_SUMMARY_PARSER_VERSION = "3.5.2";
    static final String CORE_SKILL_PROJECTION_PARSER_VERSION = "3.4.0";
    static final String DEDICATED_CANONICAL_PARSER_VERSION = "3.3.0";
    static final String LEGACY_PARSER_VERSION = "3.2.0";
    static final int MAX_RAW_RESPONSE_CHARACTERS = 100_000;
    static final int MAX_FALLBACK_TEXT_CHARACTERS = 4_000;
    // This generic defence-in-depth ceiling must remain above every reviewed
    // schema bound. The detailed release permits 120 ordinary claims, so keep
    // two-times headroom for bounded nested/provider arrays while the schema
    // continues to enforce the tighter field-specific limits.
    static final int MAX_FALLBACK_ARRAY_ITEMS = 240;
    private static final int ACTIVE_ORDINARY_CLAIM_LIMIT = 29;
    private static final int INLINE_NARRATIVE_ORDINARY_CLAIM_LIMIT = 20;
    private static final int DETAILED_INLINE_NARRATIVE_ORDINARY_CLAIM_LIMIT =
            120;
    private static final int CORE_SKILL_PROJECTION_ORDINARY_CLAIM_LIMIT = 26;
    private static final int DEDICATED_CANONICAL_ORDINARY_CLAIM_LIMIT = 38;
    private static final int MAX_HTML_DECODE_PASSES = 5;
    private static final String OPENING_CLAIM_ID = "CLAIM-9001";
    private static final String CLOSING_CLAIM_ID = "CLAIM-9002";
    private static final String PERSONAL_SUMMARY_CLAIM_ID = "CLAIM-9003";
    private static final String GENERATION_INTENT_EVIDENCE_ID =
            "REQUEST.GENERATION_INTENT";
    private static final String JOB_TITLE_EVIDENCE_ID = "JOB.TITLE";
    private static final String JOB_COMPANY_EVIDENCE_ID = "JOB.COMPANY";
    private static final String OPENING_PARAGRAPH_PATH =
            "/coverLetter/openingParagraph";
    private static final String CLOSING_PARAGRAPH_PATH =
            "/coverLetter/closingParagraph";
    private static final String PERSONAL_SUMMARY_PATH = "/cv/personalSummary";
    private static final String ORDINARY_CLAIM_ID_PATTERN =
            "^CLAIM-[0-8][0-9]{2,3}$";
    private static final String EVIDENCE_ID_PATTERN =
            "^[A-Za-z0-9._-]{3,160}$";
    private static final String ORDINARY_CONTENT_PATH_PATTERN =
            "^(?:/cv/targetRole"
                    + "|/cv/projects/[0-9]+/(?:title|role|context|startDate|endDate|description)"
                    + "|/cv/projects/[0-9]+/highlights/[0-9]+"
                    + "|/cv/qualifications/[0-9]+/(?:qualificationName|issuingBody|status|grade|dateAchieved|expectedCompletion)"
                    + "|/cv/workHistory/[0-9]+/(?:jobTitle|employer|startDate|endDate|tailoredDescription)"
                    + "|/cv/workHistory/[0-9]+/responsibilities/[0-9]+"
                    + "|/coverLetter/(?:jobTitle|companyName|bodyParagraphs/[0-9]+))$";
    private static final String INLINE_NARRATIVE_ORDINARY_CONTENT_PATH_PATTERN =
            "^(?:/cv/targetRole"
                    + "|/cv/projects/[0-9]+/(?:title|role|context|startDate|endDate|description)"
                    + "|/cv/qualifications/[0-9]+/(?:qualificationName|issuingBody|status|grade|dateAchieved|expectedCompletion)"
                    + "|/cv/workHistory/[0-9]+/(?:jobTitle|employer|startDate|endDate|tailoredDescription)"
                    + "|/coverLetter/(?:jobTitle|companyName))$";
    private static final String SELECTED_CV_INLINE_NARRATIVE_CONTENT_PATH_PATTERN =
            "^(?:/cv/targetRole"
                    + "|/cv/projects/[0-9]+/(?:title|role|context|startDate|endDate|description)"
                    + "|/cv/qualifications/[0-9]+/(?:qualificationName|issuingBody|status|grade|dateAchieved|expectedCompletion)"
                    + "|/cv/workHistory/[0-9]+/(?:jobTitle|employer|startDate|endDate|tailoredDescription))$";
    private static final String SELECTED_COVER_LETTER_INLINE_NARRATIVE_CONTENT_PATH_PATTERN =
            "^/coverLetter/(?:title|jobTitle|companyName)$";
    private static final String CORE_SKILL_PROJECTION_ORDINARY_CONTENT_PATH_PATTERN =
            "^(?:/cv/(?:title|targetRole|personalSummary)"
                    + "|/cv/projects/[0-9]+/(?:title|role|context|startDate|endDate|description)"
                    + "|/cv/projects/[0-9]+/highlights/[0-9]+"
                    + "|/cv/qualifications/[0-9]+/(?:qualificationName|issuingBody|status|grade|dateAchieved|expectedCompletion)"
                    + "|/cv/workHistory/[0-9]+/(?:jobTitle|employer|startDate|endDate|tailoredDescription)"
                    + "|/cv/workHistory/[0-9]+/responsibilities/[0-9]+"
                    + "|/coverLetter/(?:title|jobTitle|companyName|bodyParagraphs/[0-9]+))$";
    private static final String DEDICATED_CANONICAL_ORDINARY_CONTENT_PATH_PATTERN =
            "^(?:/cv(?:/[A-Za-z0-9_-]+)+|/coverLetter/"
                    + "(?:title|jobTitle|companyName|bodyParagraphs/[0-9]+))$";
    private static final Set<String> CANONICAL_APPLICATION_CLAIM_FIELDS = Set.of(
            "claimId",
            "disposition",
            "generationIntentEvidenceId",
            "jobTitleEvidenceId",
            "companyEvidenceId",
            "contentPath",
            "reviewText");
    private static final Set<String> PERSONAL_SUMMARY_CLAIM_FIELDS = Set.of(
            "claimId",
            "disposition",
            "evidenceIds",
            "contentPath",
            "reviewText");

    private static final Pattern HTML_TAG =
            Pattern.compile("(?is)<\\s*/?\\s*[a-z][^>]*>");
    private static final Pattern ACTIVE_URI =
            Pattern.compile("(?i)\\b(?:javascript|vbscript)\\s*:|\\bdata\\s*:\\s*text/html");
    private static final Pattern EVENT_HANDLER =
            Pattern.compile("(?i)\\bon[a-z]{3,20}\\s*=");
    private static final Pattern CONTROL =
            Pattern.compile("[\\p{Cc}&&[^\\r\\n\\t]]");

    private final ObjectMapper objectMapper;
    private final ClaimEvidenceValidator claimEvidenceValidator;
    private final GeneratedDocumentQualityValidator qualityValidator;

    public LlmResponseParser(
            ObjectMapper objectMapper,
            ClaimEvidenceValidator claimEvidenceValidator,
            GeneratedDocumentQualityValidator qualityValidator
    ) {
        this.objectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.claimEvidenceValidator = claimEvidenceValidator;
        this.qualityValidator = qualityValidator;
    }

    public GeneratedApplicationDocuments parse(String rawResponse, JsonNode schema) {
        return parse(rawResponse, schema, null);
    }

    public GeneratedApplicationDocuments parse(
            String rawResponse,
            JsonNode schema,
            ClaimEvidenceCatalog evidenceCatalog
    ) {
        return parseDetailed(rawResponse, schema, evidenceCatalog)
                .documents();
    }

    public ParsedGeneration parseDetailed(
            String rawResponse,
            JsonNode schema
    ) {
        return parseDetailed(rawResponse, schema, null);
    }

    public ParsedGeneration parseDetailed(
            String rawResponse,
            JsonNode schema,
            ClaimEvidenceCatalog evidenceCatalog
    ) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new InvalidLlmResponseException("LLM gateway returned an empty response");
        }
        if (rawResponse.length() > MAX_RAW_RESPONSE_CHARACTERS) {
            throw invalid("$", "response exceeds the maximum size");
        }
        if (schema == null || !schema.isObject()) {
            throw new IllegalStateException("Selected generation output schema is not an object.");
        }
        boolean usesDedicatedCanonicalClaims =
                usesDedicatedCanonicalApplicationClaims(schema);
        boolean usesDedicatedPersonalSummaryClaim =
                usesDedicatedPersonalSummaryClaim(schema);
        boolean projectsCoreSkills =
                usesDeterministicCoreSkillProjection(schema);
        boolean usesAdaptiveCoreSkillBudget =
                usesAdaptiveCoreSkillProjection(schema);
        boolean usesInlineNarrativeEvidence =
                usesInlineNarrativeEvidence(schema);

        try {
            JsonNode providerOutput = objectMapper.readTree(rawResponse);
            if (providerOutput == null) {
                throw new InvalidLlmResponseException("LLM response was not valid JSON");
            }
            validateSchema(providerOutput, schema, "$");
            validatePlainText(providerOutput, "$");
            int duplicateItemsRemoved = 0;
            if (usesInlineNarrativeEvidence) {
                duplicateItemsRemoved =
                        deduplicateInlineNarrativeArrays(providerOutput);
                // The repair is deliberately narrow, but the repaired provider
                // object must still satisfy the exact reviewed schema.
                validateSchema(providerOutput, schema, "$");
            }
            JsonNode output = usesInlineNarrativeEvidence
                    ? providerOutput.deepCopy()
                    : providerOutput;
            List<GeneratedClaim> projectedNarrativeClaims =
                    usesInlineNarrativeEvidence
                            ? projectInlineNarrativeEvidence(output)
                            : List.of();
            GeneratedApplicationDocuments documents =
                    bindDocuments(
                            output,
                            usesDedicatedCanonicalClaims,
                            usesDedicatedPersonalSummaryClaim,
                            projectedNarrativeClaims);
            if (usesDedicatedCanonicalClaims) {
                requireProjectedCanonicalApplicationClaims(documents);
            }
            if (usesDedicatedPersonalSummaryClaim) {
                requireProjectedPersonalSummaryClaim(documents);
            }
            if (evidenceCatalog != null
                    && schema.path("properties").path("claims").isObject()) {
                claimEvidenceValidator.validate(
                        output,
                        documents,
                        evidenceCatalog,
                        usesCanonicalApplicationBookendPolicy(
                                schema),
                        projectsCoreSkills,
                        usesAdaptiveCoreSkillBudget);
                validateSchema(
                        usesInlineNarrativeEvidence ? providerOutput : output,
                        schema,
                        "$");
                validatePlainText(
                        usesInlineNarrativeEvidence ? providerOutput : output,
                        "$");
                var normalizedClaims = documents.getClaims();
                documents = bindDocuments(
                        output,
                        usesDedicatedCanonicalClaims,
                        usesDedicatedPersonalSummaryClaim,
                        List.of());
                documents.setClaims(normalizedClaims);
                if (usesStructuredQualityPolicy(schema)) {
                    qualityValidator.validate(
                            output,
                            documents,
                            evidenceCatalog);
                }
            }
            return new ParsedGeneration(
                    documents,
                    new StructuralRepairReport(
                            usesInlineNarrativeEvidence,
                            duplicateItemsRemoved,
                            duplicateItemsRemoved > 0));
        } catch (JsonProcessingException exception) {
            // Parser exceptions can contain model-output fragments, so do not retain the cause.
            throw new InvalidLlmResponseException("LLM response was not valid JSON");
        }
    }

    String parserVersion(JsonNode schema) {
        boolean usesDedicatedCanonicalClaims =
                usesDedicatedCanonicalApplicationClaims(schema);
        boolean usesDedicatedPersonalSummaryClaim =
                usesDedicatedPersonalSummaryClaim(schema);
        if (usesInlineNarrativeEvidence(schema)) {
            return PARSER_VERSION;
        }
        if (usesDedicatedPersonalSummaryClaim) {
            return DEDICATED_PERSONAL_SUMMARY_PARSER_VERSION;
        }
        if (!usesDedicatedCanonicalClaims) {
            return LEGACY_PARSER_VERSION;
        }
        return usesDeterministicCoreSkillProjection(schema)
                ? CORE_SKILL_PROJECTION_PARSER_VERSION
                : DEDICATED_CANONICAL_PARSER_VERSION;
    }

    String claimPolicyVersion(JsonNode schema) {
        usesDedicatedCanonicalApplicationClaims(schema);
        usesDedicatedPersonalSummaryClaim(schema);
        return ClaimEvidenceValidator.policyVersion(
                usesDeterministicCoreSkillProjection(schema),
                usesAdaptiveCoreSkillProjection(schema));
    }

    private GeneratedApplicationDocuments bindDocuments(
            JsonNode output,
            boolean usesDedicatedCanonicalClaims,
            boolean usesDedicatedPersonalSummaryClaim,
            List<GeneratedClaim> projectedNarrativeClaims
    ) throws JsonProcessingException {
        if (!usesDedicatedCanonicalClaims
                && !usesDedicatedPersonalSummaryClaim) {
            return objectMapper.treeToValue(
                    output, GeneratedApplicationDocuments.class);
        }
        ProviderGeneratedApplicationDocuments providerDocuments =
                objectMapper.treeToValue(
                        output,
                        ProviderGeneratedApplicationDocuments.class);
        GeneratedApplicationDocuments documents =
                new GeneratedApplicationDocuments();
        documents.setCv(providerDocuments.cv());
        documents.setCoverLetter(providerDocuments.coverLetter());
        documents.setGenerationNotes(providerDocuments.generationNotes());
        List<GeneratedClaim> claims = new ArrayList<>(
                providerDocuments.claims() == null
                        ? List.of()
                        : providerDocuments.claims());
        claims.addAll(projectedNarrativeClaims);
        CanonicalApplicationClaims canonicalClaims =
                providerDocuments.canonicalApplicationClaims();
        if (canonicalClaims != null) {
            claims.add(toGeneratedClaim(canonicalClaims.opening()));
            claims.add(toGeneratedClaim(canonicalClaims.closing()));
        }
        if (usesDedicatedPersonalSummaryClaim
                && providerDocuments.personalSummaryClaim() != null) {
            claims.add(toGeneratedClaim(
                    providerDocuments.personalSummaryClaim()));
        }
        documents.setClaims(List.copyOf(claims));
        return documents;
    }

    private GeneratedClaim toGeneratedClaim(
            PersonalSummaryClaim providerClaim
    ) {
        GeneratedClaim claim = new GeneratedClaim();
        claim.setClaimId(providerClaim.claimId());
        claim.setDisposition(providerClaim.disposition());
        claim.setEvidenceIds(providerClaim.evidenceIds());
        claim.setContentPaths(List.of(providerClaim.contentPath()));
        claim.setReviewText(providerClaim.reviewText());
        return claim;
    }

    private GeneratedClaim toGeneratedClaim(
            CanonicalApplicationClaim providerClaim
    ) {
        if (providerClaim == null) {
            return null;
        }
        GeneratedClaim claim = new GeneratedClaim();
        claim.setClaimId(providerClaim.claimId());
        claim.setDisposition(providerClaim.disposition());
        claim.setEvidenceIds(List.of(
                providerClaim.generationIntentEvidenceId(),
                providerClaim.jobTitleEvidenceId(),
                providerClaim.companyEvidenceId()));
        claim.setContentPaths(List.of(providerClaim.contentPath()));
        claim.setReviewText(providerClaim.reviewText());
        return claim;
    }

    private List<GeneratedClaim> projectInlineNarrativeEvidence(
            JsonNode output
    ) {
        List<NarrativeItem> cvItems = new ArrayList<>();
        JsonNode projects = output.at("/cv/projects");
        for (int projectIndex = 0;
                projectIndex < projects.size();
                projectIndex++) {
            projectNarrativeArray(
                    output.at("/cv/projects/" + projectIndex + "/highlights"),
                    "/cv/projects/" + projectIndex + "/highlights",
                    cvItems);
        }
        JsonNode workHistory = output.at("/cv/workHistory");
        for (int workIndex = 0;
                workIndex < workHistory.size();
                workIndex++) {
            projectNarrativeArray(
                    output.at("/cv/workHistory/" + workIndex + "/responsibilities"),
                    "/cv/workHistory/" + workIndex + "/responsibilities",
                    cvItems);
        }
        List<NarrativeItem> coverLetterItems = new ArrayList<>();
        if (output.at("/coverLetter/bodyParagraphs").isArray()) {
            projectNarrativeArray(
                    output.at("/coverLetter/bodyParagraphs"),
                    "/coverLetter/bodyParagraphs",
                    coverLetterItems);
        }

        List<GeneratedClaim> claims = new ArrayList<>();
        int nextClaimNumber = appendNarrativeClaims(
                claims,
                cvItems,
                8000);
        appendNarrativeClaims(
                claims,
                coverLetterItems,
                nextClaimNumber);
        return List.copyOf(claims);
    }

    private int deduplicateInlineNarrativeArrays(JsonNode output) {
        int removed = 0;
        JsonNode projects = output.at("/cv/projects");
        for (int projectIndex = 0;
                projectIndex < projects.size();
                projectIndex++) {
            removed += deduplicateInlineNarrativeArray(output.at(
                    "/cv/projects/" + projectIndex + "/highlights"));
        }
        JsonNode workHistory = output.at("/cv/workHistory");
        for (int workIndex = 0;
                workIndex < workHistory.size();
                workIndex++) {
            removed += deduplicateInlineNarrativeArray(output.at(
                    "/cv/workHistory/" + workIndex + "/responsibilities"));
        }
        removed += deduplicateInlineNarrativeArray(
                output.at("/coverLetter/bodyParagraphs"));
        return removed;
    }

    private int deduplicateInlineNarrativeArray(JsonNode value) {
        if (!(value instanceof ArrayNode array)) {
            return 0;
        }
        int originalSize = array.size();
        LinkedHashMap<String, ObjectNode> firstByText = new LinkedHashMap<>();
        ArrayNode deduplicated = objectMapper.createArrayNode();
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode objectItem)
                    || !item.path("text").isTextual()) {
                deduplicated.add(item);
                continue;
            }
            String normalized = GeneratedDocumentQualityValidator
                    .normaliseNarrative(item.path("text").asText());
            ObjectNode first = firstByText.putIfAbsent(
                    normalized, objectItem);
            if (first == null) {
                deduplicated.add(item);
            } else {
                mergeDuplicateNarrativeEvidence(first, objectItem);
            }
        }
        array.removeAll();
        array.addAll(deduplicated);
        return originalSize - array.size();
    }

    public record ParsedGeneration(
            GeneratedApplicationDocuments documents,
            StructuralRepairReport repair) {
    }

    public record StructuralRepairReport(
            boolean attempted,
            int duplicateItemsRemoved,
            boolean succeeded) {
    }

    private void mergeDuplicateNarrativeEvidence(
            ObjectNode first,
            ObjectNode duplicate
    ) {
        LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
        first.path("evidenceIds").forEach(
                evidenceId -> evidenceIds.add(evidenceId.asText()));
        duplicate.path("evidenceIds").forEach(
                evidenceId -> evidenceIds.add(evidenceId.asText()));
        ArrayNode mergedEvidence = first.putArray("evidenceIds");
        evidenceIds.forEach(mergedEvidence::add);
        if ("REWORDED".equals(first.path("disposition").asText())
                || "REWORDED".equals(
                        duplicate.path("disposition").asText())) {
            first.put("disposition", "REWORDED");
        }
    }

    private void projectNarrativeArray(
            JsonNode value,
            String arrayPath,
            List<NarrativeItem> projected
    ) {
        if (!(value instanceof ArrayNode array)) {
            throw new IllegalStateException(
                    "Inline narrative evidence array is missing at "
                            + arrayPath + ".");
        }
        for (int index = 0; index < array.size(); index++) {
            JsonNode item = array.get(index);
            String itemPath = arrayPath + "/" + index;
            String text = item.path("text").asText();
            ClaimDisposition disposition;
            try {
                disposition = ClaimDisposition.valueOf(
                        item.path("disposition").asText());
            } catch (IllegalArgumentException exception) {
                throw invalid(
                        itemPath + "/disposition",
                        "inline narrative disposition is invalid");
            }
            List<String> evidenceIds = new ArrayList<>();
            item.path("evidenceIds").forEach(
                    evidenceId -> evidenceIds.add(evidenceId.asText()));
            LinkedHashSet<String> uniqueEvidence =
                    new LinkedHashSet<>(evidenceIds);
            require(
                    !uniqueEvidence.isEmpty()
                            && uniqueEvidence.size() == evidenceIds.size(),
                    itemPath + "/evidenceIds",
                    "inline narrative evidence IDs are empty or duplicated");
            projected.add(new NarrativeItem(
                    itemPath,
                    disposition,
                    List.copyOf(uniqueEvidence)));
            array.set(index, array.textNode(text));
        }
    }

    private int appendNarrativeClaims(
            List<GeneratedClaim> claims,
            List<NarrativeItem> items,
            int nextClaimNumber
    ) {
        for (ClaimDisposition disposition : List.of(
                ClaimDisposition.SUPPORTED,
                ClaimDisposition.REWORDED)) {
            nextClaimNumber = appendNarrativeClaims(
                    claims,
                    items.stream()
                            .filter(item -> item.disposition() == disposition)
                            .toList(),
                    disposition,
                    nextClaimNumber);
        }
        return nextClaimNumber;
    }

    private int appendNarrativeClaims(
            List<GeneratedClaim> claims,
            List<NarrativeItem> items,
            ClaimDisposition disposition,
            int nextClaimNumber
    ) {
        for (NarrativeItem item : items) {
            claims.add(narrativeClaim(
                    nextClaimNumber++,
                    disposition,
                    List.of(item.contentPath()),
                    new LinkedHashSet<>(item.evidenceIds())));
        }
        return nextClaimNumber;
    }

    private GeneratedClaim narrativeClaim(
            int claimNumber,
            ClaimDisposition disposition,
            List<String> contentPaths,
            LinkedHashSet<String> evidenceIds
    ) {
        GeneratedClaim claim = new GeneratedClaim();
        claim.setClaimId("CLAIM-" + claimNumber);
        claim.setDisposition(disposition);
        claim.setEvidenceIds(List.copyOf(evidenceIds));
        claim.setContentPaths(List.copyOf(contentPaths));
        claim.setReviewText("");
        return claim;
    }

    private void requireProjectedCanonicalApplicationClaims(
            GeneratedApplicationDocuments documents
    ) {
        List<GeneratedClaim> claims = documents.getClaims();
        requireProjectedCanonicalApplicationClaim(
                claims, OPENING_CLAIM_ID, OPENING_PARAGRAPH_PATH);
        requireProjectedCanonicalApplicationClaim(
                claims, CLOSING_CLAIM_ID, CLOSING_PARAGRAPH_PATH);
    }

    private void requireProjectedPersonalSummaryClaim(
            GeneratedApplicationDocuments documents
    ) {
        long matches = documents.getClaims() == null
                ? 0
                : documents.getClaims().stream()
                        .filter(claim -> claim != null)
                        .filter(claim -> PERSONAL_SUMMARY_CLAIM_ID.equals(
                                claim.getClaimId()))
                        .filter(claim -> claim.getDisposition()
                                        == ClaimDisposition.SUPPORTED
                                || claim.getDisposition()
                                        == ClaimDisposition.REWORDED)
                        .filter(claim -> claim.getEvidenceIds() != null
                                && !claim.getEvidenceIds().isEmpty()
                                && claim.getEvidenceIds().size() <= 30)
                        .filter(claim -> List.of(PERSONAL_SUMMARY_PATH)
                                .equals(claim.getContentPaths()))
                        .filter(claim -> "".equals(claim.getReviewText()))
                        .count();
        if (matches != 1) {
            throw new IllegalStateException(
                    "Dedicated personal-summary claim projection failed.");
        }
    }

    private void requireProjectedCanonicalApplicationClaim(
            List<GeneratedClaim> claims,
            String claimId,
            String contentPath
    ) {
        long matches = claims == null
                ? 0
                : claims.stream()
                        .filter(claim -> claim != null)
                        .filter(claim -> claimId.equals(claim.getClaimId()))
                        .filter(claim -> claim.getDisposition()
                                == ClaimDisposition.SUPPORTED)
                        .filter(claim -> List.of(
                                        GENERATION_INTENT_EVIDENCE_ID,
                                        JOB_TITLE_EVIDENCE_ID,
                                        JOB_COMPANY_EVIDENCE_ID)
                                .equals(claim.getEvidenceIds()))
                        .filter(claim -> List.of(contentPath)
                                .equals(claim.getContentPaths()))
                        .filter(claim -> "".equals(claim.getReviewText()))
                        .count();
        if (matches != 1) {
            throw new IllegalStateException(
                    "Dedicated canonical application claim projection failed.");
        }
    }

    private boolean usesDedicatedCanonicalApplicationClaims(
            JsonNode schema
    ) {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()
                || !properties.has("canonicalApplicationClaims")) {
            requireNoPartialDedicatedCanonicalApplicationContract(properties);
            return false;
        }
        JsonNode canonicalClaims =
                properties.path("canonicalApplicationClaims");
        requireExactObjectSchema(
                canonicalClaims,
                Set.of("opening", "closing"),
                "$.canonicalApplicationClaims");
        requireExactCanonicalApplicationClaimSchema(
                canonicalClaims.path("properties").path("opening"),
                OPENING_CLAIM_ID,
                OPENING_PARAGRAPH_PATH,
                "$.canonicalApplicationClaims.opening");
        requireExactCanonicalApplicationClaimSchema(
                canonicalClaims.path("properties").path("closing"),
                CLOSING_CLAIM_ID,
                CLOSING_PARAGRAPH_PATH,
                "$.canonicalApplicationClaims.closing");

        JsonNode ordinaryClaim =
                properties.path("claims").path("items").path("properties");
        String claimIdPattern = ordinaryClaim.path("claimId")
                .path("pattern").asText();
        String contentPathPattern = ordinaryClaim.path("contentPaths")
                .path("items").path("pattern").asText();
        requireSchemaContract(
                ORDINARY_CLAIM_ID_PATTERN.equals(claimIdPattern),
                "ordinary claim ID namespace is incomplete");
        requireSchemaContract(
                ORDINARY_CONTENT_PATH_PATTERN.equals(contentPathPattern)
                        || INLINE_NARRATIVE_ORDINARY_CONTENT_PATH_PATTERN.equals(
                                contentPathPattern)
                        || SELECTED_COVER_LETTER_INLINE_NARRATIVE_CONTENT_PATH_PATTERN.equals(
                                contentPathPattern)
                        || CORE_SKILL_PROJECTION_ORDINARY_CONTENT_PATH_PATTERN.equals(
                                contentPathPattern)
                        || DEDICATED_CANONICAL_ORDINARY_CONTENT_PATH_PATTERN.equals(
                                contentPathPattern),
                "ordinary content-path allowlist is incomplete");
        try {
            Pattern ordinaryClaimIds = Pattern.compile(claimIdPattern);
            Pattern ordinaryPaths = Pattern.compile(contentPathPattern);
            requireSchemaContract(
                    !ordinaryClaimIds.matcher(OPENING_CLAIM_ID).find()
                            && !ordinaryClaimIds.matcher(CLOSING_CLAIM_ID).find()
                            && !ordinaryClaimIds.matcher(
                                    PERSONAL_SUMMARY_CLAIM_ID).find(),
                    "ordinary claim IDs do not reserve canonical IDs");
            requireSchemaContract(
                    !ordinaryPaths.matcher(OPENING_PARAGRAPH_PATH).find()
                            && !ordinaryPaths.matcher(CLOSING_PARAGRAPH_PATH).find(),
                    "ordinary claims can cover canonical bookends");
        } catch (PatternSyntaxException exception) {
            throw new IllegalStateException(
                    "Dedicated canonical application claim schema is invalid.",
                    exception);
        }
        Set<Integer> expectedOrdinaryClaimLimits;
        if (ORDINARY_CONTENT_PATH_PATTERN.equals(contentPathPattern)) {
            expectedOrdinaryClaimLimits = Set.of(ACTIVE_ORDINARY_CLAIM_LIMIT);
        } else if (INLINE_NARRATIVE_ORDINARY_CONTENT_PATH_PATTERN.equals(
                        contentPathPattern)
                || SELECTED_COVER_LETTER_INLINE_NARRATIVE_CONTENT_PATH_PATTERN.equals(
                        contentPathPattern)) {
            expectedOrdinaryClaimLimits = Set.of(
                    INLINE_NARRATIVE_ORDINARY_CLAIM_LIMIT,
                    DETAILED_INLINE_NARRATIVE_ORDINARY_CLAIM_LIMIT);
        } else if (CORE_SKILL_PROJECTION_ORDINARY_CONTENT_PATH_PATTERN.equals(
                contentPathPattern)) {
            expectedOrdinaryClaimLimits = Set.of(
                    CORE_SKILL_PROJECTION_ORDINARY_CLAIM_LIMIT);
        } else {
            expectedOrdinaryClaimLimits = Set.of(
                    DEDICATED_CANONICAL_ORDINARY_CLAIM_LIMIT);
        }
        requireSchemaContract(
                properties.path("claims").path("maxItems").isIntegralNumber()
                        && expectedOrdinaryClaimLimits.contains(
                                properties.path("claims").path("maxItems")
                                        .asInt()),
                "ordinary claim bound does not reserve canonical capacity");
        return true;
    }

    private boolean usesDedicatedPersonalSummaryClaim(
            JsonNode schema
    ) {
        JsonNode properties = schema.path("properties");
        String contentPathPattern = properties.path("claims")
                .path("items").path("properties")
                .path("contentPaths").path("items")
                .path("pattern").asText();
        boolean activeContract = ORDINARY_CONTENT_PATH_PATTERN.equals(
                contentPathPattern)
                || INLINE_NARRATIVE_ORDINARY_CONTENT_PATH_PATTERN.equals(
                        contentPathPattern)
                || SELECTED_CV_INLINE_NARRATIVE_CONTENT_PATH_PATTERN.equals(
                        contentPathPattern);
        if (!activeContract) {
            requireSchemaContract(
                    !properties.has("personalSummaryClaim"),
                    "personal-summary claim is present outside its dedicated contract");
            return false;
        }
        requireSchemaContract(
                properties.has("personalSummaryClaim"),
                "personal-summary claim object is missing");
        JsonNode claimSchema = properties.path("personalSummaryClaim");
        requireExactObjectSchema(
                claimSchema,
                PERSONAL_SUMMARY_CLAIM_FIELDS,
                "$.personalSummaryClaim");
        JsonNode claimProperties = claimSchema.path("properties");
        requireExactSingletonStringSchema(
                claimProperties.path("claimId"),
                PERSONAL_SUMMARY_CLAIM_ID,
                "$.personalSummaryClaim.claimId");
        requireSchemaContract(
                "^CLAIM-9003$".equals(claimProperties.path("claimId")
                        .path("pattern").asText()),
                "claim ID pattern is not fixed at $.personalSummaryClaim.claimId");
        requireFinalDispositionSchema(
                claimProperties.path("disposition"),
                "$.personalSummaryClaim.disposition");
        requireEvidenceIdsSchema(
                claimProperties.path("evidenceIds"),
                schema,
                "$.personalSummaryClaim.evidenceIds");
        requireExactSingletonStringSchema(
                claimProperties.path("contentPath"),
                PERSONAL_SUMMARY_PATH,
                "$.personalSummaryClaim.contentPath");
        requireSchemaContract(
                "^/cv/personalSummary$".equals(
                        claimProperties.path("contentPath")
                                .path("pattern").asText()),
                "content-path pattern is not fixed at $.personalSummaryClaim.contentPath");
        requireExactSingletonStringSchema(
                claimProperties.path("reviewText"),
                "",
                "$.personalSummaryClaim.reviewText");
        return true;
    }

    private void requireFinalDispositionSchema(
            JsonNode schema,
            String path
    ) {
        Set<String> values = new HashSet<>();
        schema.path("enum").forEach(value -> values.add(value.asText()));
        requireSchemaContract(
                "string".equals(schema.path("type").asText())
                        && "^(SUPPORTED|REWORDED)$".equals(
                                schema.path("pattern").asText())
                        && values.equals(Set.of("SUPPORTED", "REWORDED"))
                        && schema.path("enum").size() == 2,
                "final disposition is incomplete at " + path);
    }

    private void requireEvidenceIdsSchema(
            JsonNode schema,
            JsonNode rootSchema,
            String path
    ) {
        JsonNode itemContract = schema.path("items");
        boolean usesReference = itemContract.path("$ref").isTextual();
        JsonNode items = resolveLocalReference(
                itemContract,
                rootSchema,
                path + ".items");
        requireSchemaContract(
                "array".equals(schema.path("type").asText())
                        && schema.path("minItems").asInt(-1) == 1
                        && schema.path("maxItems").asInt(-1) == 30
                        && "string".equals(items.path("type").asText())
                        && EVIDENCE_ID_PATTERN.equals(
                                items.path("pattern").asText())
                        && (!usesReference
                                || (items.path("enum").isArray()
                                        && !items.path("enum").isEmpty())),
                "evidence ID list is incomplete at " + path);
    }

    private void requireNoPartialDedicatedCanonicalApplicationContract(
            JsonNode properties
    ) {
        if (!properties.isObject()) {
            return;
        }
        JsonNode ordinaryClaims = properties.path("claims");
        String claimIdPattern = ordinaryClaims.path("items")
                .path("properties").path("claimId")
                .path("pattern").asText();
        String contentPathPattern = ordinaryClaims.path("items")
                .path("properties").path("contentPaths")
                .path("items").path("pattern").asText();
        if (SELECTED_CV_INLINE_NARRATIVE_CONTENT_PATH_PATTERN.equals(
                        contentPathPattern)
                && properties.has("cv")
                && !properties.has("coverLetter")
                && properties.has("personalSummaryClaim")) {
            return;
        }
        boolean dedicatedMarker =
                ORDINARY_CLAIM_ID_PATTERN.equals(claimIdPattern)
                        || ORDINARY_CONTENT_PATH_PATTERN.equals(
                                contentPathPattern)
                        || INLINE_NARRATIVE_ORDINARY_CONTENT_PATH_PATTERN.equals(
                                contentPathPattern)
                        || CORE_SKILL_PROJECTION_ORDINARY_CONTENT_PATH_PATTERN.equals(
                                contentPathPattern)
                        || DEDICATED_CANONICAL_ORDINARY_CONTENT_PATH_PATTERN.equals(
                                contentPathPattern)
                        || properties.has("personalSummaryClaim")
                        || (ordinaryClaims.path("maxItems").isIntegralNumber()
                                && (ordinaryClaims.path("maxItems").asInt()
                                                == ACTIVE_ORDINARY_CLAIM_LIMIT
                                        || ordinaryClaims.path("maxItems").asInt()
                                                == CORE_SKILL_PROJECTION_ORDINARY_CLAIM_LIMIT
                                        || ordinaryClaims.path("maxItems").asInt()
                                                == INLINE_NARRATIVE_ORDINARY_CLAIM_LIMIT
                                        || ordinaryClaims.path("maxItems").asInt()
                                                == DETAILED_INLINE_NARRATIVE_ORDINARY_CLAIM_LIMIT
                                        || ordinaryClaims.path("maxItems").asInt()
                                                == DEDICATED_CANONICAL_ORDINARY_CLAIM_LIMIT));
        requireSchemaContract(
                !dedicatedMarker,
                "canonical claim object is missing from a dedicated ordinary-claim contract");
    }

    private void requireExactCanonicalApplicationClaimSchema(
            JsonNode claimSchema,
            String claimId,
            String contentPath,
            String path
    ) {
        requireExactObjectSchema(
                claimSchema,
                CANONICAL_APPLICATION_CLAIM_FIELDS,
                path);
        JsonNode properties = claimSchema.path("properties");
        requireExactSingletonStringSchema(
                properties.path("claimId"), claimId, path + ".claimId");
        requireExactSingletonStringSchema(
                properties.path("disposition"),
                ClaimDisposition.SUPPORTED.name(),
                path + ".disposition");
        requireExactSingletonStringSchema(
                properties.path("generationIntentEvidenceId"),
                GENERATION_INTENT_EVIDENCE_ID,
                path + ".generationIntentEvidenceId");
        requireExactSingletonStringSchema(
                properties.path("jobTitleEvidenceId"),
                JOB_TITLE_EVIDENCE_ID,
                path + ".jobTitleEvidenceId");
        requireExactSingletonStringSchema(
                properties.path("companyEvidenceId"),
                JOB_COMPANY_EVIDENCE_ID,
                path + ".companyEvidenceId");
        requireExactSingletonStringSchema(
                properties.path("contentPath"),
                contentPath,
                path + ".contentPath");
        requireExactSingletonStringSchema(
                properties.path("reviewText"), "", path + ".reviewText");
    }

    private void requireExactObjectSchema(
            JsonNode schema,
            Set<String> expectedFields,
            String path
    ) {
        Set<String> properties = new HashSet<>();
        schema.path("properties").fieldNames()
                .forEachRemaining(properties::add);
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(field -> required.add(field.asText()));
        requireSchemaContract(
                "object".equals(schema.path("type").asText())
                        && !schema.path("additionalProperties").asBoolean(true)
                        && properties.equals(expectedFields)
                        && required.equals(expectedFields)
                        && schema.path("required").size() == expectedFields.size(),
                "object shape is incomplete at " + path);
    }

    private void requireExactSingletonStringSchema(
            JsonNode schema,
            String expected,
            String path
    ) {
        requireSchemaContract(
                "string".equals(schema.path("type").asText())
                        && schema.path("pattern").isTextual()
                        && isExactSingletonEnum(schema, expected),
                "singleton field is incomplete at " + path);
        try {
            requireSchemaContract(
                    Pattern.compile(schema.path("pattern").asText())
                            .matcher(expected).find(),
                    "singleton pattern rejects its value at " + path);
        } catch (PatternSyntaxException exception) {
            throw new IllegalStateException(
                    "Dedicated canonical application claim schema is invalid.",
                    exception);
        }
    }

    private void requireSchemaContract(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalStateException(
                    "Dedicated canonical application claim schema is invalid: "
                            + reason + ".");
        }
    }

    private boolean usesStructuredQualityPolicy(JsonNode schema) {
        return schema.at(
                        "/properties/cv/properties/projects")
                .isObject();
    }

    private boolean usesDeterministicCoreSkillProjection(
            JsonNode schema
    ) {
        String contentPathPattern = schema.at(
                        "/properties/claims/items/properties/contentPaths/items/pattern")
                .asText();
        return ORDINARY_CONTENT_PATH_PATTERN.equals(contentPathPattern)
                || INLINE_NARRATIVE_ORDINARY_CONTENT_PATH_PATTERN.equals(
                        contentPathPattern)
                || SELECTED_CV_INLINE_NARRATIVE_CONTENT_PATH_PATTERN.equals(
                        contentPathPattern)
                || CORE_SKILL_PROJECTION_ORDINARY_CONTENT_PATH_PATTERN.equals(
                        contentPathPattern);
    }

    private boolean usesAdaptiveCoreSkillProjection(
            JsonNode schema
    ) {
        String contentPathPattern = schema.at(
                        "/properties/claims/items/properties/contentPaths/items/pattern")
                .asText();
        return ORDINARY_CONTENT_PATH_PATTERN.equals(contentPathPattern)
                || INLINE_NARRATIVE_ORDINARY_CONTENT_PATH_PATTERN.equals(
                        contentPathPattern)
                || SELECTED_CV_INLINE_NARRATIVE_CONTENT_PATH_PATTERN.equals(
                        contentPathPattern)
                || SELECTED_COVER_LETTER_INLINE_NARRATIVE_CONTENT_PATH_PATTERN
                        .equals(contentPathPattern);
    }

    private boolean usesInlineNarrativeEvidence(JsonNode schema) {
        String contentPathPattern = schema.at(
                        "/properties/claims/items/properties/contentPaths/items/pattern")
                .asText();
        if (!Set.of(
                        INLINE_NARRATIVE_ORDINARY_CONTENT_PATH_PATTERN,
                        SELECTED_CV_INLINE_NARRATIVE_CONTENT_PATH_PATTERN,
                        SELECTED_COVER_LETTER_INLINE_NARRATIVE_CONTENT_PATH_PATTERN)
                .contains(contentPathPattern)) {
            return false;
        }
        if (schema.at("/properties/cv").isObject()) {
            requireInlineNarrativeItemSchema(
                    schema.at(
                            "/properties/cv/properties/projects/items/properties/highlights/items"),
                    schema,
                    "$.cv.projects[].highlights[]");
            requireInlineNarrativeItemSchema(
                    schema.at(
                            "/properties/cv/properties/workHistory/items/properties/responsibilities/items"),
                    schema,
                    "$.cv.workHistory[].responsibilities[]");
        }
        if (schema.at("/properties/coverLetter").isObject()) {
            requireInlineNarrativeItemSchema(
                    schema.at(
                            "/properties/coverLetter/properties/bodyParagraphs/items"),
                    schema,
                    "$.coverLetter.bodyParagraphs[]");
        }
        return true;
    }

    private void requireInlineNarrativeItemSchema(
            JsonNode itemSchema,
            JsonNode rootSchema,
            String path
    ) {
        requireExactObjectSchema(
                itemSchema,
                Set.of("text", "disposition", "evidenceIds"),
                path);
        JsonNode properties = itemSchema.path("properties");
        requireSchemaContract(
                "string".equals(properties.path("text").path("type").asText())
                        && properties.path("text").path("pattern").isTextual(),
                "inline narrative text is incomplete at " + path);
        requireFinalDispositionSchema(
                properties.path("disposition"),
                path + ".disposition");
        requireEvidenceIdsSchema(
                properties.path("evidenceIds"),
                rootSchema,
                path + ".evidenceIds");
    }

    private boolean usesCanonicalApplicationBookendPolicy(
            JsonNode schema
    ) {
        return isExactSingletonEnum(
                        schema.at(
                                "/properties/coverLetter/properties/openingParagraph"),
                        "Please consider my application for this role.")
                && isExactSingletonEnum(
                        schema.at(
                                "/properties/coverLetter/properties/closingParagraph"),
                        "Thank you for considering my application.");
    }

    private boolean isExactSingletonEnum(
            JsonNode propertySchema,
            String expected
    ) {
        JsonNode values = propertySchema.path("enum");
        return values.isArray()
                && values.size() == 1
                && values.get(0).isTextual()
                && expected.equals(values.get(0).textValue());
    }

    private void validateSchema(JsonNode value, JsonNode schema, String path) {
        validateSchema(value, schema, schema, path);
    }

    private void validateSchema(
            JsonNode value,
            JsonNode schema,
            JsonNode rootSchema,
            String path
    ) {
        JsonNode resolvedSchema = resolveLocalReference(schema, rootSchema, path);
        String type = resolvedSchema.path("type").asText();
        switch (type) {
            case "object" -> validateObject(value, resolvedSchema, rootSchema, path);
            case "array" -> validateArray(value, resolvedSchema, rootSchema, path);
            case "string" -> validateString(value, resolvedSchema, path);
            case "boolean" -> require(value.isBoolean(), path, "expected boolean");
            case "integer" -> require(value.isIntegralNumber(), path, "expected integer");
            case "number" -> require(value.isNumber(), path, "expected number");
            default -> throw new IllegalStateException(
                    "Generation output schema contains unsupported type at " + path);
        }
        validateEnum(value, resolvedSchema, path);
    }

    private JsonNode resolveLocalReference(
            JsonNode schema,
            JsonNode rootSchema,
            String path
    ) {
        JsonNode reference = schema.path("$ref");
        if (!reference.isTextual()) {
            return schema;
        }
        String pointer = reference.asText();
        if (!pointer.startsWith("#/")) {
            throw new IllegalStateException(
                    "Generation output schema contains a non-local reference at " + path);
        }
        JsonNode resolved = rootSchema.at(pointer.substring(1));
        if (resolved.isMissingNode() || !resolved.isObject()) {
            throw new IllegalStateException(
                    "Generation output schema contains an unresolved reference at " + path);
        }
        return resolved;
    }

    private void validateObject(
            JsonNode value,
            JsonNode schema,
            JsonNode rootSchema,
            String path
    ) {
        require(value.isObject(), path, "expected object");
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            throw new IllegalStateException(
                    "Generation output schema properties are invalid at " + path);
        }

        JsonNode required = schema.path("required");
        if (!required.isArray()) {
            throw new IllegalStateException(
                    "Generation output schema required fields are invalid at " + path);
        }
        for (JsonNode requiredName : required) {
            String field = requiredName.asText();
            require(value.has(field) && !value.get(field).isNull(),
                    child(path, field), "required field is missing");
        }

        Set<String> allowed = new HashSet<>();
        properties.fieldNames().forEachRemaining(allowed::add);
        if (!schema.path("additionalProperties").asBoolean(true)) {
            Iterator<String> supplied = value.fieldNames();
            while (supplied.hasNext()) {
                String field = supplied.next();
                require(allowed.contains(field), child(path, field), "unknown field");
            }
        }

        Iterator<String> fields = properties.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (value.has(field)) {
                validateSchema(
                        value.get(field),
                        properties.get(field),
                        rootSchema,
                        child(path, field));
            }
        }
    }

    private void validateArray(
            JsonNode value,
            JsonNode schema,
            JsonNode rootSchema,
            String path
    ) {
        require(value.isArray(), path, "expected array");
        int minimum = schema.path("minItems").asInt(0);
        int maximum = schema.path("maxItems").asInt(Integer.MAX_VALUE);
        require(value.size() >= minimum, path, "contains too few items");
        require(value.size() <= maximum, path, "contains too many items");
        JsonNode itemSchema = schema.path("items");
        if (!itemSchema.isObject()) {
            throw new IllegalStateException(
                    "Generation output schema array items are invalid at " + path);
        }
        for (int index = 0; index < value.size(); index++) {
            validateSchema(
                    value.get(index),
                    itemSchema,
                    rootSchema,
                    path + "[" + index + "]");
        }
    }

    private void validateString(JsonNode value, JsonNode schema, String path) {
        require(value.isTextual(), path, "expected string");
        if (schema.has("pattern")) {
            try {
                Pattern pattern = Pattern.compile(schema.get("pattern").asText());
                require(pattern.matcher(value.textValue()).find(),
                        path, "does not satisfy the bounded text policy");
            } catch (PatternSyntaxException exception) {
                throw new IllegalStateException(
                        "Generation output schema contains an invalid pattern at " + path,
                        exception);
            }
        }
    }

    private void validateEnum(JsonNode value, JsonNode schema, String path) {
        JsonNode allowed = schema.path("enum");
        if (!allowed.isArray()) {
            return;
        }
        for (JsonNode candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        throw invalid(path, "value is outside the allowed set");
    }

    private void validatePlainText(JsonNode value, String path) {
        if (value.isTextual()) {
            String text = fullyUnescapeHtml(value.textValue(), path);
            require(text.length() <= MAX_FALLBACK_TEXT_CHARACTERS,
                    path, "text exceeds the parser safety limit");
            require(!HTML_TAG.matcher(text).find(), path, "active or markup content is forbidden");
            require(!ACTIVE_URI.matcher(text).find(), path, "active or markup content is forbidden");
            require(!EVENT_HANDLER.matcher(text).find(), path, "active or markup content is forbidden");
            require(!CONTROL.matcher(text).find(), path, "control characters are forbidden");
            return;
        }
        if (value.isArray()) {
            require(value.size() <= MAX_FALLBACK_ARRAY_ITEMS,
                    path, "array exceeds the parser safety limit");
            for (int index = 0; index < value.size(); index++) {
                validatePlainText(value.get(index), path + "[" + index + "]");
            }
            return;
        }
        if (value.isObject()) {
            value.fields().forEachRemaining(
                    field -> validatePlainText(field.getValue(), child(path, field.getKey())));
        }
    }

    private String fullyUnescapeHtml(String value, String path) {
        String decoded = value;
        for (int pass = 0; pass < MAX_HTML_DECODE_PASSES; pass++) {
            String next = HtmlUtils.htmlUnescape(decoded);
            if (next.equals(decoded)) {
                return decoded;
            }
            decoded = next;
        }
        require(HtmlUtils.htmlUnescape(decoded).equals(decoded),
                path, "excessively encoded text is forbidden");
        return decoded;
    }

    private String child(String path, String field) {
        return "$".equals(path) ? "$." + field : path + "." + field;
    }

    private void require(boolean condition, String path, String reason) {
        if (!condition) {
            throw invalid(path, reason);
        }
    }

    private InvalidLlmResponseException invalid(String path, String reason) {
        return new InvalidLlmResponseException(
                "LLM response failed safe output validation at " + path + ": " + reason);
    }

    private record ProviderGeneratedApplicationDocuments(
            GeneratedCv cv,
            GeneratedCoverLetter coverLetter,
            GenerationNotes generationNotes,
            List<GeneratedClaim> claims,
            CanonicalApplicationClaims canonicalApplicationClaims,
            PersonalSummaryClaim personalSummaryClaim) {
    }

    private record CanonicalApplicationClaims(
            CanonicalApplicationClaim opening,
            CanonicalApplicationClaim closing) {
    }

    private record CanonicalApplicationClaim(
            String claimId,
            ClaimDisposition disposition,
            String generationIntentEvidenceId,
            String jobTitleEvidenceId,
            String companyEvidenceId,
            String contentPath,
            String reviewText) {
    }

    private record PersonalSummaryClaim(
            String claimId,
            ClaimDisposition disposition,
            List<String> evidenceIds,
            String contentPath,
            String reviewText) {
    }

    private record NarrativeItem(
            String contentPath,
            ClaimDisposition disposition,
            List<String> evidenceIds) {
    }
}
