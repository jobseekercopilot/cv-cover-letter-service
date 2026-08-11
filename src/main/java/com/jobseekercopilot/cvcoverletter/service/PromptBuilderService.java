package com.jobseekercopilot.cvcoverletter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobseekercopilot.cvcoverletter.config.LlmProperties;
import com.jobseekercopilot.cvcoverletter.dto.DraftOutputType;
import com.jobseekercopilot.cvcoverletter.dto.InputWarning;
import com.jobseekercopilot.cvcoverletter.dto.PromptGenerationMetadata;
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
            Set.of("1.5.8", "1.5.9", "1.5.10", "1.5.11", "1.6.0");
    private static final Set<String> SEPARATED_DECLARED_SKILLS_RULES_VERSIONS =
            Set.of("1.5.9", "1.5.10", "1.5.11", "1.6.0");

    private final ObjectMapper objectMapper;
    private final PromptBundleRegistry promptBundleRegistry;
    private final LlmProperties llmProperties;
    private final ClaimEvidenceCatalogFactory evidenceCatalogFactory;

    public CvCoverLetterPrompt buildPrompt(NormalizedGenerationInput input) {
        return buildPrompt(input, promptBundleRegistry.selected(), null);
    }

    public CvCoverLetterPrompt buildPrompt(
            NormalizedGenerationInput input,
            DraftOutputType outputType) {
        if (outputType == null) {
            throw new IllegalArgumentException("Selected draft output is required.");
        }
        return buildPrompt(input, promptBundleRegistry.selected(), outputType);
    }

    public CvCoverLetterPrompt buildPrompt(
            NormalizedGenerationInput input,
            String approvedReleaseId) {
        return buildPrompt(
                input, promptBundleRegistry.get(approvedReleaseId), null);
    }

    public CvCoverLetterPrompt buildPrompt(
            NormalizedGenerationInput input,
            String approvedReleaseId,
            DraftOutputType outputType) {
        if (outputType == null) {
            throw new IllegalArgumentException("Selected draft output is required.");
        }
        return buildPrompt(
                input,
                promptBundleRegistry.get(approvedReleaseId),
                outputType);
    }

    private CvCoverLetterPrompt buildPrompt(
            NormalizedGenerationInput input,
            PromptBundle bundle,
            DraftOutputType outputType) {
        try {
            String template = bundle.template();
            String rules = selectedRules(bundle.rules(), outputType);
            String outputSchemaJson = bundle.outputSchemaJson();
            boolean includeRevisionDeclaredSkills =
                    RULES_VERSIONS_WITH_CANONICAL_PROFILE_SKILLS.contains(
                            bundle.metadata().rulesVersion());
            ClaimEvidenceCatalog evidenceCatalog = outputType == null
                    ? evidenceCatalogFactory.create(
                            input,
                            includeRevisionDeclaredSkills)
                    : evidenceCatalogFactory.create(input, outputType);
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
                    SEPARATED_DECLARED_SKILLS_RULES_VERSIONS.contains(
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
            if (outputType != null) {
                outputSchema = selectedOutputSchema(
                        outputSchema, outputType);
            }
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
                    .generationMetadata(selectedMetadata(
                            bundle.metadata(), outputType))
                    .evidenceCatalog(evidenceCatalog)
                    .build();

        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to build CV and cover letter prompt", e);
        }
    }

    private PromptGenerationMetadata selectedMetadata(
            PromptGenerationMetadata metadata,
            DraftOutputType outputType) {
        if (outputType == null) {
            return metadata;
        }
        return new PromptGenerationMetadata(
                metadata.releaseId(),
                metadata.bundleId(),
                metadata.bundleVersion(),
                metadata.bundleSha256(),
                metadata.templateVersion(),
                metadata.templateSha256(),
                metadata.rulesVersion(),
                metadata.rulesSha256(),
                metadata.schemaId()
                        + "-"
                        + outputType.name().toLowerCase(java.util.Locale.ROOT),
                metadata.schemaVersion(),
                metadata.schemaSha256(),
                metadata.evaluationPolicyVersion(),
                metadata.evaluationPolicySha256());
    }

    private String selectedRules(
            String rules,
            DraftOutputType outputType) {
        if (outputType == null) {
            return rules;
        }
        String name = outputType == DraftOutputType.CV
                ? "CV"
                : "cover letter";
        return "SELECTED OUTPUT CONTRACT: Generate only the requested "
                + name
                + "; ignore shared rules for the unselected document.\n\n"
                + rules;
    }

    private JsonNode selectedOutputSchema(
            JsonNode source,
            DraftOutputType outputType) {
        if (!(source.deepCopy() instanceof ObjectNode selected)) {
            throw new IllegalStateException(
                    "Selected prompt bundle output schema is not an object.");
        }
        ObjectNode properties = (ObjectNode) selected.path("properties");
        ArrayNode required = (ArrayNode) selected.path("required");
        if (outputType == DraftOutputType.CV) {
            properties.remove("coverLetter");
            properties.remove("canonicalApplicationClaims");
            replaceRequired(
                    required,
                    Set.of(
                            "cv",
                            "generationNotes",
                            "personalSummaryClaim",
                            "claims"));
            selectedClaimPathPattern(
                    properties,
                    "^(?:/cv/targetRole"
                            + "|/cv/projects/[0-9]+/(?:title|role|context|startDate|endDate|description)"
                            + "|/cv/qualifications/[0-9]+/(?:qualificationName|issuingBody|status|grade|dateAchieved|expectedCompletion)"
                            + "|/cv/workHistory/[0-9]+/(?:jobTitle|employer|startDate|endDate|tailoredDescription)"
                            + ")$");
        } else {
            properties.remove("cv");
            properties.remove("personalSummaryClaim");
            replaceRequired(
                    required,
                    Set.of(
                            "coverLetter",
                            "generationNotes",
                            "canonicalApplicationClaims",
                            "claims"));
            selectedClaimPathPattern(
                    properties,
                    "^/coverLetter/(?:title|jobTitle|companyName)$");
        }
        return selected;
    }

    private void selectedClaimPathPattern(
            ObjectNode properties,
            String pattern) {
        ((ObjectNode) properties.path("claims")
                .path("items")
                .path("properties")
                .path("contentPaths")
                .path("items"))
                .put("pattern", pattern);
    }

    private void replaceRequired(
            ArrayNode required,
            Set<String> fields) {
        required.removeAll();
        fields.stream().sorted().forEach(required::add);
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
        if (!SEPARATED_DECLARED_SKILLS_RULES_VERSIONS.contains(rulesVersion)) {
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
        String evidenceIdPattern = outputSchema.at(
                        "/properties/claims/items/properties/evidenceIds/items/pattern")
                .asText();
        if (evidenceIdPattern.isBlank()) {
            throw new IllegalStateException(
                    "Selected prompt bundle evidenceIds item pattern is missing.");
        }
        evidenceIdSchema.put("pattern", evidenceIdPattern);
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
        setOptionalEvidenceIdItemReference(
                outputSchema.at(
                        "/properties/cv/properties/projects/items/properties/highlights/items/properties/evidenceIds"),
                evidenceIdReference);
        setOptionalEvidenceIdItemReference(
                outputSchema.at(
                        "/properties/cv/properties/workHistory/items/properties/responsibilities/items/properties/evidenceIds"),
                evidenceIdReference);
        setOptionalEvidenceIdItemReference(
                outputSchema.at(
                        "/properties/coverLetter/properties/bodyParagraphs/items/properties/evidenceIds"),
                evidenceIdReference);
    }

    private void setOptionalEvidenceIdItemReference(
            JsonNode evidenceIdsSchema,
            ObjectNode evidenceIdReference
    ) {
        if (!evidenceIdsSchema.isMissingNode()) {
            setEvidenceIdItemReference(evidenceIdsSchema, evidenceIdReference);
        }
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
