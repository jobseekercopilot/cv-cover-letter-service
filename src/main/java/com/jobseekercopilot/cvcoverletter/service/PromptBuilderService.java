package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.dto.InputWarning;
import com.jobseekercopilot.cvcoverletter.exception.InvalidGenerationInputException;
import com.jobseekercopilot.cvcoverletter.model.ClaimEvidenceCatalog;
import com.jobseekercopilot.cvcoverletter.model.CvCoverLetterPrompt;
import com.jobseekercopilot.cvcoverletter.model.EvidenceSource;
import com.jobseekercopilot.cvcoverletter.model.NormalizedGenerationInput;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PromptBuilderService {
    static final int MAX_TRUSTED_INSTRUCTION_CHARACTERS = 12_000;
    static final int MAX_UNTRUSTED_INPUT_CHARACTERS = 170_000;
    static final int MAX_OUTPUT_SCHEMA_CHARACTERS = 64_000;
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\{\\{[A-Z0-9_]+}}");
    private static final String SEPARATE_SCHEMA_MARKER =
            "[STRICT JSON SCHEMA SUPPLIED THROUGH THE OUTPUT CONTRACT]";
    private static final String SEPARATE_PROFILE_MARKER =
            "[PROFILE EVIDENCE SUPPLIED THROUGH THE UNTRUSTED INPUT CHANNEL]";
    private static final String SEPARATE_JOB_MARKER =
            "[CANONICAL JOB FACTS SUPPLIED THROUGH THE UNTRUSTED INPUT CHANNEL]";
    private static final String SEPARATE_WARNINGS_MARKER =
            "[INPUT WARNINGS SUPPLIED THROUGH THE UNTRUSTED INPUT CHANNEL]";
    private static final Set<String> RULES_VERSIONS_WITH_CANONICAL_PROFILE_SKILLS =
            Set.of("1.5.8", "1.5.9");
    private static final String SEPARATED_DECLARED_SKILLS_RULES_VERSION =
            "1.5.9";

    private final ObjectMapper objectMapper;
    private final PromptBundleRegistry promptBundleRegistry;
    private final LlmProperties llmProperties;
    private final ClaimEvidenceCatalogFactory evidenceCatalogFactory;

    public CvCoverLetterPrompt buildPrompt(NormalizedGenerationInput input) {
        try {
            PromptBundle bundle = promptBundleRegistry.selected();
            String template = bundle.template();
            String rules = bundle.rules();
            String outputSchemaJson = bundle.outputSchemaJson();
            boolean includeRevisionDeclaredSkills =
                    RULES_VERSIONS_WITH_CANONICAL_PROFILE_SKILLS.contains(
                            bundle.metadata().rulesVersion());
            ClaimEvidenceCatalog evidenceCatalog = evidenceCatalogFactory.create(
                    input,
                    includeRevisionDeclaredSkills);
            ClaimEvidenceCatalog approvedEvidence = approvedEvidence(
                    evidenceCatalog,
                    bundle.metadata().rulesVersion());

            String trustedInstructions = template
                    .replace("{{LANGUAGE}}", llmProperties.getLanguage())
                    .replace("{{PROMPT_BUNDLE_ID}}", bundle.metadata().bundleId())
                    .replace("{{PROMPT_BUNDLE_VERSION}}", bundle.metadata().bundleVersion())
                    .replace("{{TEMPLATE_VERSION}}", bundle.metadata().templateVersion())
                    .replace("{{RULES_VERSION}}", bundle.metadata().rulesVersion())
                    .replace("{{SCHEMA_ID}}", bundle.metadata().schemaId())
                    .replace("{{SCHEMA_VERSION}}", bundle.metadata().schemaVersion())
                    .replace("{{RULES}}", rules)
                    .replace("{{OUTPUT_SCHEMA_JSON}}", SEPARATE_SCHEMA_MARKER)
                    .replace("{{PROFILE_INPUT_JSON}}", SEPARATE_PROFILE_MARKER)
                    .replace("{{JOB_INPUT_JSON}}", SEPARATE_JOB_MARKER)
                    .replace("{{INPUT_WARNINGS_JSON}}", SEPARATE_WARNINGS_MARKER);
            if (UNRESOLVED_PLACEHOLDER.matcher(trustedInstructions).find()) {
                throw new IllegalStateException(
                        "Selected prompt bundle contains an unresolved contract placeholder.");
            }
            Object untrustedPromptInput =
                    SEPARATED_DECLARED_SKILLS_RULES_VERSION.equals(
                            bundle.metadata().rulesVersion())
                            ? separatedDeclaredSkillInput(
                                    evidenceCatalog,
                                    approvedEvidence,
                                    input.warnings())
                            : new UntrustedGenerationInput(
                                    "UNTRUSTED_DATA_ONLY",
                                    approvedEvidence,
                                    input.warnings());
            String untrustedInput = toPrettyJson(untrustedPromptInput);
            JsonNode outputShape = objectMapper.readTree(outputSchemaJson);
            if (outputShape == null || !outputShape.isObject()) {
                throw new IllegalStateException("Selected prompt bundle output schema is not an object.");
            }
            JsonNode outputSchema = isStrictJsonSchema(outputShape)
                    ? outputShape.deepCopy()
                    : compileStrictJsonSchema(outputShape);
            bindApprovedEvidenceIds(outputSchema, approvedEvidence);
            requireWithinBoundary(
                    "trusted instructions",
                    trustedInstructions,
                    MAX_TRUSTED_INSTRUCTION_CHARACTERS);
            requireWithinBoundary(
                    "untrusted input",
                    untrustedInput,
                    MAX_UNTRUSTED_INPUT_CHARACTERS);
            requireWithinBoundary(
                    "output schema",
                    outputSchema.toString(),
                    MAX_OUTPUT_SCHEMA_CHARACTERS);

            return CvCoverLetterPrompt.builder()
                    .taskType(llmProperties.getTaskType())
                    .trustedInstructions(trustedInstructions)
                    .untrustedInput(untrustedInput)
                    .outputSchema(outputSchema)
                    .generationMetadata(bundle.metadata())
                    .evidenceCatalog(evidenceCatalog)
                    .build();

        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to build CV and cover letter prompt", e);
        }
    }

    private String toPrettyJson(Object value) throws JsonProcessingException {
        return objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(value);
    }

    private void requireWithinBoundary(String field, String value, int maximum) {
        if (value.length() > maximum) {
            throw new InvalidGenerationInputException(
                    field + ": exceeds LLM Gateway v2 limit of " + maximum + " characters");
        }
    }

    private JsonNode compileStrictJsonSchema(JsonNode shape) {
        if (shape.isObject()) {
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");
            Iterator<Map.Entry<String, JsonNode>> fields = shape.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                properties.set(field.getKey(), compileStrictJsonSchema(field.getValue()));
                required.add(field.getKey());
            }
            schema.put("additionalProperties", false);
            return schema;
        }
        if (shape.isArray()) {
            if (shape.size() != 1) {
                throw new IllegalStateException(
                        "Prompt bundle output shape arrays must contain exactly one item template.");
            }
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "array");
            schema.set("items", compileStrictJsonSchema(shape.get(0)));
            return schema;
        }
        if (shape.isTextual()) {
            return objectMapper.createObjectNode().put("type", "string");
        }
        if (shape.isBoolean()) {
            return objectMapper.createObjectNode().put("type", "boolean");
        }
        if (shape.isIntegralNumber()) {
            return objectMapper.createObjectNode().put("type", "integer");
        }
        if (shape.isFloatingPointNumber()) {
            return objectMapper.createObjectNode().put("type", "number");
        }
        throw new IllegalStateException(
                "Prompt bundle output shape contains an unsupported value type: " + shape.getNodeType());
    }

    private boolean isStrictJsonSchema(JsonNode schema) {
        return "object".equals(schema.path("type").asText())
                && schema.path("properties").isObject()
                && schema.path("required").isArray()
                && schema.path("additionalProperties").isBoolean();
    }

    private SeparatedDeclaredSkillInput separatedDeclaredSkillInput(
            ClaimEvidenceCatalog evidenceCatalog,
            ClaimEvidenceCatalog approvedEvidence,
            List<InputWarning> inputWarnings
    ) {
        List<String> candidates = evidenceCatalog.records().stream()
                .filter(record -> record.source()
                        == EvidenceSource.PROFILE_REVISION)
                .map(record -> record.value())
                .distinct()
                .toList();
        return new SeparatedDeclaredSkillInput(
                "UNTRUSTED_DATA_ONLY",
                approvedEvidence,
                candidates,
                inputWarnings);
    }

    private ClaimEvidenceCatalog approvedEvidence(
            ClaimEvidenceCatalog evidenceCatalog,
            String rulesVersion
    ) {
        if (!SEPARATED_DECLARED_SKILLS_RULES_VERSION.equals(rulesVersion)) {
            return evidenceCatalog;
        }
        return new ClaimEvidenceCatalog(
                evidenceCatalog.catalogVersion(),
                evidenceCatalog.records().stream()
                        .filter(record -> record.source()
                                != EvidenceSource.PROFILE_REVISION)
                        .toList(),
                evidenceCatalog.sectionOrder());
    }

    private void bindApprovedEvidenceIds(
            JsonNode outputSchema,
            ClaimEvidenceCatalog approvedEvidence
    ) {
        var evidenceIds = new LinkedHashSet<String>();
        approvedEvidence.records().forEach(record -> evidenceIds.add(record.evidenceId()));
        if (evidenceIds.isEmpty()) {
            throw new InvalidGenerationInputException(
                    "approved evidence: no claimable evidence IDs are available");
        }

        ObjectNode evidenceIdSchema = objectMapper.createObjectNode();
        evidenceIdSchema.put("type", "string");
        ArrayNode allowedEvidenceIds = evidenceIdSchema.putArray("enum");
        evidenceIds.forEach(allowedEvidenceIds::add);

        ObjectNode definitions = objectMapper.createObjectNode();
        definitions.set("approvedEvidenceId", evidenceIdSchema);
        ((ObjectNode) outputSchema).set("$defs", definitions);

        ObjectNode evidenceIdReference = objectMapper.createObjectNode();
        evidenceIdReference.put("$ref", "#/$defs/approvedEvidenceId");
        JsonNode personalSummaryEvidenceIds = outputSchema.at(
                "/properties/personalSummaryClaim/properties/evidenceIds");
        if (!personalSummaryEvidenceIds.isMissingNode()) {
            setEvidenceIdItemReference(
                    personalSummaryEvidenceIds,
                    evidenceIdReference);
        }
        setEvidenceIdItemReference(
                outputSchema.at(
                        "/properties/claims/items/properties/evidenceIds"),
                evidenceIdReference);
    }

    private void setEvidenceIdItemReference(
            JsonNode evidenceIdsSchema,
            ObjectNode evidenceIdReference
    ) {
        if (!(evidenceIdsSchema instanceof ObjectNode evidenceIds)
                || !"array".equals(evidenceIds.path("type").asText())) {
            throw new IllegalStateException(
                    "Selected prompt bundle evidenceIds schema is not an array.");
        }
        evidenceIds.set("items", evidenceIdReference.deepCopy());
    }

    private record UntrustedGenerationInput(
            String classification,
            ClaimEvidenceCatalog approvedEvidence,
            List<InputWarning> inputWarnings
    ) {
    }

    private record SeparatedDeclaredSkillInput(
            String classification,
            ClaimEvidenceCatalog approvedEvidence,
            List<String> serviceProjectedCoreSkillCandidates,
            List<InputWarning> inputWarnings
    ) {
    }
}
