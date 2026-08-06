package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class LlmResponseParser {

    static final String PARSER_VERSION = "3.4.0";
    static final String DEDICATED_CANONICAL_PARSER_VERSION = "3.3.0";
    static final String LEGACY_PARSER_VERSION = "3.2.0";
    static final int MAX_RAW_RESPONSE_CHARACTERS = 100_000;
    static final int MAX_FALLBACK_TEXT_CHARACTERS = 4_000;
    static final int MAX_FALLBACK_ARRAY_ITEMS = 40;
    private static final int ACTIVE_ORDINARY_CLAIM_LIMIT = 26;
    private static final int ROLLBACK_ORDINARY_CLAIM_LIMIT = 38;
    private static final int MAX_HTML_DECODE_PASSES = 5;
    private static final String OPENING_CLAIM_ID = "CLAIM-9001";
    private static final String CLOSING_CLAIM_ID = "CLAIM-9002";
    private static final String GENERATION_INTENT_EVIDENCE_ID =
            "REQUEST.GENERATION_INTENT";
    private static final String JOB_TITLE_EVIDENCE_ID = "JOB.TITLE";
    private static final String JOB_COMPANY_EVIDENCE_ID = "JOB.COMPANY";
    private static final String OPENING_PARAGRAPH_PATH =
            "/coverLetter/openingParagraph";
    private static final String CLOSING_PARAGRAPH_PATH =
            "/coverLetter/closingParagraph";
    private static final String ORDINARY_CLAIM_ID_PATTERN =
            "^CLAIM-[0-8][0-9]{2,3}$";
    private static final String ORDINARY_CONTENT_PATH_PATTERN =
            "^(?:/cv/(?:title|targetRole|personalSummary)"
                    + "|/cv/projects/[0-9]+/(?:title|role|context|startDate|endDate|description)"
                    + "|/cv/projects/[0-9]+/highlights/[0-9]+"
                    + "|/cv/qualifications/[0-9]+/(?:qualificationName|issuingBody|status|grade|dateAchieved|expectedCompletion)"
                    + "|/cv/workHistory/[0-9]+/(?:jobTitle|employer|startDate|endDate|tailoredDescription)"
                    + "|/cv/workHistory/[0-9]+/responsibilities/[0-9]+"
                    + "|/coverLetter/(?:title|jobTitle|companyName|bodyParagraphs/[0-9]+))$";
    private static final String ROLLBACK_ORDINARY_CONTENT_PATH_PATTERN =
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
        boolean projectsCoreSkills =
                usesDeterministicCoreSkillProjection(schema);

        try {
            JsonNode output = objectMapper.readTree(rawResponse);
            if (output == null) {
                throw new InvalidLlmResponseException("LLM response was not valid JSON");
            }
            validateSchema(output, schema, "$");
            validatePlainText(output, "$");
            GeneratedApplicationDocuments documents =
                    bindDocuments(output, usesDedicatedCanonicalClaims);
            if (usesDedicatedCanonicalClaims) {
                requireProjectedCanonicalApplicationClaims(documents);
            }
            if (evidenceCatalog != null
                    && schema.path("properties").path("claims").isObject()) {
                claimEvidenceValidator.validate(
                        output,
                        documents,
                        evidenceCatalog,
                        usesCanonicalApplicationBookendPolicy(
                                schema),
                        projectsCoreSkills);
                validateSchema(output, schema, "$");
                validatePlainText(output, "$");
                var normalizedClaims = documents.getClaims();
                documents = bindDocuments(
                        output, usesDedicatedCanonicalClaims);
                documents.setClaims(normalizedClaims);
                if (usesStructuredQualityPolicy(schema)) {
                    qualityValidator.validate(
                            output,
                            documents,
                            evidenceCatalog);
                }
            }
            return documents;
        } catch (JsonProcessingException exception) {
            // Parser exceptions can contain model-output fragments, so do not retain the cause.
            throw new InvalidLlmResponseException("LLM response was not valid JSON");
        }
    }

    String parserVersion(JsonNode schema) {
        if (!usesDedicatedCanonicalApplicationClaims(schema)) {
            return LEGACY_PARSER_VERSION;
        }
        return usesDeterministicCoreSkillProjection(schema)
                ? PARSER_VERSION
                : DEDICATED_CANONICAL_PARSER_VERSION;
    }

    String claimPolicyVersion(JsonNode schema) {
        boolean dedicated =
                usesDedicatedCanonicalApplicationClaims(schema);
        return ClaimEvidenceValidator.policyVersion(
                dedicated
                        && usesDeterministicCoreSkillProjection(schema));
    }

    private GeneratedApplicationDocuments bindDocuments(
            JsonNode output,
            boolean usesDedicatedCanonicalClaims
    ) throws JsonProcessingException {
        if (!usesDedicatedCanonicalClaims) {
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
        CanonicalApplicationClaims canonicalClaims =
                providerDocuments.canonicalApplicationClaims();
        if (canonicalClaims != null) {
            claims.add(toGeneratedClaim(canonicalClaims.opening()));
            claims.add(toGeneratedClaim(canonicalClaims.closing()));
        }
        documents.setClaims(List.copyOf(claims));
        return documents;
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

    private void requireProjectedCanonicalApplicationClaims(
            GeneratedApplicationDocuments documents
    ) {
        List<GeneratedClaim> claims = documents.getClaims();
        requireProjectedCanonicalApplicationClaim(
                claims, OPENING_CLAIM_ID, OPENING_PARAGRAPH_PATH);
        requireProjectedCanonicalApplicationClaim(
                claims, CLOSING_CLAIM_ID, CLOSING_PARAGRAPH_PATH);
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
                        || ROLLBACK_ORDINARY_CONTENT_PATH_PATTERN.equals(
                                contentPathPattern),
                "ordinary content-path allowlist is incomplete");
        try {
            Pattern ordinaryClaimIds = Pattern.compile(claimIdPattern);
            Pattern ordinaryPaths = Pattern.compile(contentPathPattern);
            requireSchemaContract(
                    !ordinaryClaimIds.matcher(OPENING_CLAIM_ID).find()
                            && !ordinaryClaimIds.matcher(CLOSING_CLAIM_ID).find(),
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
        int expectedOrdinaryClaimLimit =
                ORDINARY_CONTENT_PATH_PATTERN.equals(contentPathPattern)
                        ? ACTIVE_ORDINARY_CLAIM_LIMIT
                        : ROLLBACK_ORDINARY_CLAIM_LIMIT;
        requireSchemaContract(
                properties.path("claims").path("maxItems").isIntegralNumber()
                        && properties.path("claims").path("maxItems").asInt()
                                == expectedOrdinaryClaimLimit,
                "ordinary claim bound does not reserve canonical capacity");
        return true;
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
        boolean dedicatedMarker =
                ORDINARY_CLAIM_ID_PATTERN.equals(claimIdPattern)
                        || ORDINARY_CONTENT_PATH_PATTERN.equals(
                                contentPathPattern)
                        || ROLLBACK_ORDINARY_CONTENT_PATH_PATTERN.equals(
                                contentPathPattern)
                        || (ordinaryClaims.path("maxItems").isIntegralNumber()
                                && (ordinaryClaims.path("maxItems").asInt()
                                                == ACTIVE_ORDINARY_CLAIM_LIMIT
                                        || ordinaryClaims.path("maxItems").asInt()
                                                == ROLLBACK_ORDINARY_CLAIM_LIMIT));
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
        return ORDINARY_CONTENT_PATH_PATTERN.equals(
                schema.at(
                                "/properties/claims/items/properties/contentPaths/items/pattern")
                        .asText());
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
        String type = schema.path("type").asText();
        switch (type) {
            case "object" -> validateObject(value, schema, path);
            case "array" -> validateArray(value, schema, path);
            case "string" -> validateString(value, schema, path);
            case "boolean" -> require(value.isBoolean(), path, "expected boolean");
            case "integer" -> require(value.isIntegralNumber(), path, "expected integer");
            case "number" -> require(value.isNumber(), path, "expected number");
            default -> throw new IllegalStateException(
                    "Generation output schema contains unsupported type at " + path);
        }
        validateEnum(value, schema, path);
    }

    private void validateObject(JsonNode value, JsonNode schema, String path) {
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
                validateSchema(value.get(field), properties.get(field), child(path, field));
            }
        }
    }

    private void validateArray(JsonNode value, JsonNode schema, String path) {
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
            validateSchema(value.get(index), itemSchema, path + "[" + index + "]");
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
            CanonicalApplicationClaims canonicalApplicationClaims) {
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
}
