package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.dto.GeneratedApplicationDocuments;
import com.jobseekercopilot.cvcoverletter.exception.InvalidLlmResponseException;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class LlmResponseParser {

    static final String PARSER_VERSION = "3.1.0";
    static final int MAX_RAW_RESPONSE_CHARACTERS = 100_000;
    static final int MAX_FALLBACK_TEXT_CHARACTERS = 4_000;
    static final int MAX_FALLBACK_ARRAY_ITEMS = 40;
    private static final int MAX_HTML_DECODE_PASSES = 5;

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

        try {
            JsonNode output = objectMapper.readTree(rawResponse);
            if (output == null) {
                throw new InvalidLlmResponseException("LLM response was not valid JSON");
            }
            validateSchema(output, schema, "$");
            validatePlainText(output, "$");
            GeneratedApplicationDocuments documents =
                    objectMapper.treeToValue(output, GeneratedApplicationDocuments.class);
            if (evidenceCatalog != null
                    && schema.path("properties").path("claims").isObject()) {
                claimEvidenceValidator.validate(output, documents, evidenceCatalog);
                var normalizedClaims = documents.getClaims();
                documents = objectMapper.treeToValue(
                        output,
                        GeneratedApplicationDocuments.class);
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

    private boolean usesStructuredQualityPolicy(JsonNode schema) {
        return schema.at(
                        "/properties/cv/properties/projects")
                .isObject();
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
}
