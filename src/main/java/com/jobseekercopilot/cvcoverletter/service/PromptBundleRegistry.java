package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.cvcoverletter.config.PromptBundleProperties;
import com.jobseekercopilot.cvcoverletter.dto.PromptGenerationMetadata;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PromptBundleRegistry {
    private static final String INDEX_LOCATION = "classpath:prompts/bundles/index.json";
    private static final Pattern RELEASE_ID = Pattern.compile("[a-z0-9][a-z0-9.-]{2,63}");
    private static final Pattern SEMANTIC_VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern PROVIDER_INSTRUCTION =
            Pattern.compile("(?i)\\b(openai|anthropic|gemini|chatgpt|chat completions|responses api)\\b");
    private static final List<String> REQUIRED_TEMPLATE_PLACEHOLDERS = List.of(
            "{{LANGUAGE}}",
            "{{RULES}}",
            "{{OUTPUT_SCHEMA_JSON}}",
            "{{PROFILE_INPUT_JSON}}",
            "{{JOB_INPUT_JSON}}",
            "{{INPUT_WARNINGS_JSON}}"
    );

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final PromptBundleProperties properties;
    private Map<String, PromptBundle> bundles = Map.of();
    private String defaultReleaseId;

    public PromptBundleRegistry(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            PromptBundleProperties properties
    ) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        try {
            PromptBundleIndex index = objectMapper.readValue(readBytes(INDEX_LOCATION), PromptBundleIndex.class);
            validateIndex(index);
            Map<String, PromptBundle> loaded = new LinkedHashMap<>();
            for (String releaseId : index.approvedReleaseIds()) {
                loaded.put(releaseId, loadBundle(releaseId));
            }
            defaultReleaseId = index.defaultReleaseId();
            long activeCount = loaded.values().stream()
                    .filter(bundle -> "ACTIVE".equals(bundle.releaseStatus()))
                    .count();
            if (activeCount != 1
                    || !"ACTIVE".equals(loaded.get(defaultReleaseId).releaseStatus())) {
                throw new IllegalStateException(
                        "Prompt bundle registry must have exactly one ACTIVE default release.");
            }
            String selected = selectedReleaseId();
            if (!loaded.containsKey(selected)) {
                throw new IllegalStateException(
                        "Selected prompt bundle is not in the packaged approved index: " + selected);
            }
            bundles = Map.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("Prompt bundle registry could not be loaded.", exception);
        }
    }

    PromptBundle selected() {
        return get(selectedReleaseId());
    }

    PromptBundle get(String releaseId) {
        PromptBundle bundle = bundles.get(releaseId);
        if (bundle == null) {
            throw new IllegalArgumentException("Prompt bundle is not approved: " + releaseId);
        }
        return bundle;
    }

    public Set<String> approvedReleaseIds() {
        return bundles.keySet();
    }

    public PromptBundleComparison compare(String fromReleaseId, String toReleaseId) {
        PromptGenerationMetadata from = get(fromReleaseId).metadata();
        PromptGenerationMetadata to = get(toReleaseId).metadata();
        return new PromptBundleComparison(
                fromReleaseId,
                toReleaseId,
                !from.templateSha256().equals(to.templateSha256()),
                !from.rulesSha256().equals(to.rulesSha256()),
                !from.schemaSha256().equals(to.schemaSha256()),
                !from.evaluationPolicyVersion().equals(to.evaluationPolicyVersion())
                        || !from.evaluationPolicySha256().equals(to.evaluationPolicySha256())
        );
    }

    private PromptBundle loadBundle(String releaseId) throws IOException {
        validateReleaseId(releaseId);
        String base = "classpath:prompts/bundles/" + releaseId + "/";
        PromptBundleManifest manifest =
                objectMapper.readValue(readBytes(base + "manifest.json"), PromptBundleManifest.class);
        validateManifest(releaseId, manifest);

        byte[] templateBytes = readBytes(base + "cv-cover-letter-prompt-template.txt");
        byte[] rulesBytes = readBytes(base + "generation-rules.txt");
        byte[] schemaBytes = readBytes(base + "output-schema.json");
        byte[] evaluationPolicyBytes = readBytes(base + "evaluation-policy.json");
        requireHash("template", manifest.templateSha256(), templateBytes);
        requireHash("rules", manifest.rulesSha256(), rulesBytes);
        requireHash("schema", manifest.schemaSha256(), schemaBytes);
        requireHash("evaluation policy", manifest.evaluationPolicySha256(), evaluationPolicyBytes);

        String template = new String(templateBytes, StandardCharsets.UTF_8);
        String rules = new String(rulesBytes, StandardCharsets.UTF_8);
        String schema = new String(schemaBytes, StandardCharsets.UTF_8);
        for (String placeholder : REQUIRED_TEMPLATE_PLACEHOLDERS) {
            if (!template.contains(placeholder)) {
                throw new IllegalStateException(
                        "Prompt template is missing required placeholder " + placeholder + " in " + releaseId);
            }
        }
        JsonNode schemaNode = objectMapper.readTree(schemaBytes);
        if (schemaNode == null || !schemaNode.isObject()) {
            throw new IllegalStateException("Prompt output schema must be a JSON object in " + releaseId);
        }
        if ("ACTIVE".equals(manifest.releaseStatus())) {
            validateStrictOutputSchema(schemaNode, "$");
        }
        JsonNode evaluationPolicyNode = objectMapper.readTree(evaluationPolicyBytes);
        if (evaluationPolicyNode == null
                || !evaluationPolicyNode.isObject()
                || !manifest.evaluationPolicyVersion().equals(
                        evaluationPolicyNode.path("policyVersion").asText())) {
            throw new IllegalStateException(
                    "Prompt evaluation policy must be a matching versioned JSON object in " + releaseId);
        }
        if (PROVIDER_INSTRUCTION.matcher(template).find() || PROVIDER_INSTRUCTION.matcher(rules).find()) {
            throw new IllegalStateException(
                    "Provider-specific instructions are forbidden in domain prompt bundle " + releaseId);
        }

        String bundleSha256 = sha256(String.join("|",
                manifest.releaseId(),
                manifest.bundleId(),
                manifest.bundleVersion(),
                manifest.templateVersion(),
                manifest.rulesVersion(),
                manifest.schemaId(),
                manifest.schemaVersion(),
                manifest.evaluationPolicyVersion(),
                manifest.evaluationPolicySha256(),
                manifest.templateSha256(),
                manifest.rulesSha256(),
                manifest.schemaSha256()).getBytes(StandardCharsets.UTF_8));
        PromptGenerationMetadata metadata = new PromptGenerationMetadata(
                manifest.releaseId(),
                manifest.bundleId(),
                manifest.bundleVersion(),
                bundleSha256,
                manifest.templateVersion(),
                manifest.templateSha256(),
                manifest.rulesVersion(),
                manifest.rulesSha256(),
                manifest.schemaId(),
                manifest.schemaVersion(),
                manifest.schemaSha256(),
                manifest.evaluationPolicyVersion(),
                manifest.evaluationPolicySha256()
        );
        return new PromptBundle(metadata, manifest.releaseStatus(), template, rules, schema);
    }

    private void validateIndex(PromptBundleIndex index) {
        if (index == null
                || !StringUtils.hasText(index.defaultReleaseId())
                || index.approvedReleaseIds() == null
                || index.approvedReleaseIds().isEmpty()) {
            throw new IllegalStateException("Prompt bundle index must define a default and approved releases.");
        }
        validateReleaseId(index.defaultReleaseId());
        Set<String> unique = new HashSet<>(index.approvedReleaseIds());
        if (unique.size() != index.approvedReleaseIds().size()) {
            throw new IllegalStateException("Prompt bundle index contains duplicate releases.");
        }
        index.approvedReleaseIds().forEach(this::validateReleaseId);
        if (!unique.contains(index.defaultReleaseId())) {
            throw new IllegalStateException("Default prompt bundle must be in the approved release index.");
        }
    }

    private void validateManifest(String releaseId, PromptBundleManifest manifest) {
        if (manifest == null
                || !releaseId.equals(manifest.releaseId())
                || !StringUtils.hasText(manifest.bundleId())
                || !SEMANTIC_VERSION.matcher(value(manifest.bundleVersion())).matches()
                || !SEMANTIC_VERSION.matcher(value(manifest.templateVersion())).matches()
                || !SEMANTIC_VERSION.matcher(value(manifest.rulesVersion())).matches()
                || !SEMANTIC_VERSION.matcher(value(manifest.schemaVersion())).matches()
                || !SEMANTIC_VERSION.matcher(value(manifest.evaluationPolicyVersion())).matches()
                || !StringUtils.hasText(manifest.schemaId())
                || !Set.of("ACTIVE", "ROLLBACK").contains(manifest.releaseStatus())
                || !SHA_256.matcher(value(manifest.templateSha256())).matches()
                || !SHA_256.matcher(value(manifest.rulesSha256())).matches()
                || !SHA_256.matcher(value(manifest.schemaSha256())).matches()
                || !SHA_256.matcher(value(manifest.evaluationPolicySha256())).matches()) {
            throw new IllegalStateException("Prompt bundle manifest is invalid for " + releaseId);
        }
    }

    private void validateStrictOutputSchema(JsonNode schema, String path) {
        String type = schema.path("type").asText();
        Set<String> keys = new HashSet<>();
        schema.fieldNames().forEachRemaining(keys::add);
        switch (type) {
            case "object" -> {
                requireSchema(
                        keys.equals(Set.of("type", "properties", "required", "additionalProperties")),
                        path,
                        "object keywords are not in the approved provider subset");
                JsonNode propertiesNode = schema.path("properties");
                JsonNode requiredNode = schema.path("required");
                requireSchema(propertiesNode.isObject(), path, "properties must be an object");
                requireSchema(requiredNode.isArray(), path, "required must be an array");
                requireSchema(
                        schema.path("additionalProperties").isBoolean()
                                && !schema.path("additionalProperties").asBoolean(),
                        path,
                        "additionalProperties must be false");
                Set<String> properties = new HashSet<>();
                propertiesNode.fieldNames().forEachRemaining(properties::add);
                Set<String> required = new HashSet<>();
                requiredNode.forEach(field -> required.add(field.asText()));
                requireSchema(
                        required.size() == requiredNode.size() && required.equals(properties),
                        path,
                        "every property must be required exactly once");
                propertiesNode.fields().forEachRemaining(field ->
                        validateStrictOutputSchema(field.getValue(), child(path, field.getKey())));
            }
            case "array" -> {
                requireSchema(
                        keys.equals(Set.of("type", "items", "minItems", "maxItems")),
                        path,
                        "array keywords are not in the approved provider subset");
                requireSchema(schema.path("items").isObject(), path, "items must be an object");
                requireSchema(
                        schema.path("minItems").canConvertToInt()
                                && schema.path("maxItems").canConvertToInt()
                                && schema.path("minItems").asInt() >= 0
                                && schema.path("maxItems").asInt() >= schema.path("minItems").asInt(),
                        path,
                        "array bounds are invalid");
                validateStrictOutputSchema(schema.path("items"), path + "[]");
            }
            case "string" -> {
                requireSchema(
                        keys.equals(Set.of("type", "pattern")),
                        path,
                        "string keywords are not in the approved provider subset");
                requireSchema(schema.path("pattern").isTextual(), path, "string pattern is required");
                try {
                    Pattern.compile(schema.path("pattern").asText());
                } catch (RuntimeException exception) {
                    throw new IllegalStateException(
                            "Active output schema is invalid at " + path + ": pattern is invalid",
                            exception);
                }
            }
            default -> throw new IllegalStateException(
                    "Active output schema is invalid at " + path + ": unsupported type");
        }
    }

    private String child(String path, String field) {
        return "$".equals(path) ? "$." + field : path + "." + field;
    }

    private void requireSchema(boolean condition, String path, String message) {
        if (!condition) {
            throw new IllegalStateException(
                    "Active output schema is invalid at " + path + ": " + message);
        }
    }

    private String selectedReleaseId() {
        String selected = properties.getSelectedReleaseId();
        return StringUtils.hasText(selected) ? selected.trim() : defaultReleaseId;
    }

    private void validateReleaseId(String releaseId) {
        if (releaseId == null || !RELEASE_ID.matcher(releaseId).matches()) {
            throw new IllegalStateException("Prompt bundle release ID is invalid.");
        }
    }

    private void requireHash(String component, String expected, byte[] content) {
        if (!expected.equals(sha256(content))) {
            throw new IllegalStateException("Prompt bundle " + component + " checksum does not match its manifest.");
        }
    }

    private byte[] readBytes(String location) throws IOException {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalStateException("Required prompt bundle resource is missing: " + location);
        }
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        }
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private record PromptBundleIndex(String defaultReleaseId, List<String> approvedReleaseIds) {
    }

    private record PromptBundleManifest(
            String releaseId,
            String bundleId,
            String bundleVersion,
            String releaseStatus,
            String templateVersion,
            String rulesVersion,
            String schemaId,
            String schemaVersion,
            String evaluationPolicyVersion,
            String evaluationPolicySha256,
            String templateSha256,
            String rulesSha256,
            String schemaSha256
    ) {
    }
}
