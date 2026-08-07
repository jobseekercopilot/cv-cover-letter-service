#!/usr/bin/env bash
set -euo pipefail

contract_dir="${1:-contracts}"
contract="$contract_dir/openapi.json"
manifest="$contract_dir/SHA256SUMS"

for required_file in "$contract" "$manifest"; do
    if [[ ! -f "$required_file" || -L "$required_file" ]]; then
        echo "API contract policy: required regular file is missing or is a symlink: $required_file" >&2
        exit 1
    fi
done

(
    cd "$contract_dir"
    sha256sum --check --strict SHA256SUMS
)

jq -e '
    .components.schemas as $schemas |
    [
        "ContactInputSnapshot",
        "DraftGenerationAudit",
        "DraftGenerationEstimateResponse",
        "DraftGenerationResponse",
        "DraftGenerationUsage",
        "EmploymentInput",
        "EvidenceSnapshotFactInput",
        "EvidenceSnapshotInput",
        "EvidenceSnapshotSelectionInput",
        "EvidenceSnapshotsInput",
        "GenerateRequest",
        "JobInputSnapshot",
        "PromptGenerationMetadata",
        "ProfileInputSnapshot",
        "QualificationInput",
        "SnapshotProvenance",
        "ValidatedClaim",
        "ValidatedClaimLedger"
    ] as $closed |
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "4.0.0") and
    (.paths["/api/v1/cv-cover-letter/drafts/estimate"].post.operationId
        == "estimateDraft") and
    (.paths["/api/v1/cv-cover-letter/drafts"].post.operationId
        == "generateDraft") and
    (.paths["/api/v1/cv-cover-letter/drafts"].post.parameters
        | any(.name == "X-Document-Owner" and .in == "header" and
              .required == true and .schema.type == "string")) and
    (.paths["/api/v1/cv-cover-letter/drafts"].post.parameters
        | any(.name == "X-Generation-Operation-Id" and .in == "header" and
              .required == true and .schema.type == "string" and
              .schema.format == "uuid")) and
    (.paths["/api/v1/cv-cover-letter/drafts"].post.security
        | any(has("serviceToken"))) and
    (.paths["/api/v1/cv-cover-letter/drafts"].post.responses["200"].content["*/*"].schema["$ref"]
        == "#/components/schemas/DraftGenerationResponse") and
    (.paths["/api/v1/cv-cover-letter/drafts/estimate"].post.responses["200"].content["*/*"].schema["$ref"]
        == "#/components/schemas/DraftGenerationEstimateResponse") and
    ($schemas.DraftGenerationEstimateResponse.required
        | index("estimatedTokens") != null) and
    ($schemas.DraftGenerationResponse.required
        | index("operationId") != null and index("cvTitle") != null and
          index("coverLetterTitle") != null and index("cvContent") != null and
          index("coverLetterContent") != null and
          index("claimLedger") != null and index("usage") != null and
          index("audit") != null) and
    ($schemas.DraftGenerationResponse.properties.claimLedger["$ref"]
        == "#/components/schemas/ValidatedClaimLedger") and
    ($schemas.ValidatedClaimLedger.required
        | index("ledgerId") != null and index("ledgerSha256") != null and
          index("policyVersion") != null and index("parserVersion") != null and
          index("claims") != null) and
    ($schemas.ValidatedClaimLedger.properties.ledgerSha256.pattern
        == "^[a-f0-9]{64}$") and
    ($schemas.ValidatedClaimLedger.properties.claims.maxItems == 40) and
    ($schemas.ValidatedClaim.required
        | index("claimId") != null and index("disposition") != null and
          index("evidenceIds") != null and index("contentPaths") != null and
          index("reviewText") != null) and
    ($schemas.DraftGenerationResponse.properties
        | has("applicationId") | not) and
    ($schemas.DraftGenerationResponse.properties
        | has("cvDocumentId") | not) and
    ($schemas.DraftGenerationResponse.properties
        | has("coverLetterDocumentId") | not) and
    ($schemas.DraftGenerationUsage.properties
        | has("inputTokens") and has("outputTokens") and has("totalTokens")) and
    ($schemas.DraftGenerationAudit.properties
        | has("modelId") and has("modelDeploymentVersion") and
          has("admissionPolicyVersion") and has("pricingVersion") and
          has("estimatedInputTokensAtAdmission") and
          has("estimatedCostMicroUsd") and has("currency")) and
    (.paths["/api/v1/cv-cover-letter/generate"].post.operationId == "generate") and
    (.paths["/api/v1/cv-cover-letter/generate"].post.parameters
        | any(.name == "X-Document-Owner" and .in == "header" and
              .required == true and .schema.type == "string")) and
    (.paths["/api/v1/cv-cover-letter/generate"].post.parameters
        | all(.name != "X-User-Id")) and
    (.paths["/api/v1/cv-cover-letter/generate"].post.security
        | any(has("serviceToken"))) and
    (.components.securitySchemes.serviceToken.type == "apiKey") and
    (.components.securitySchemes.serviceToken.in == "header") and
    (.components.securitySchemes.serviceToken.name == "X-Service-Token") and
    (.paths["/api/v1/cv-cover-letter/generate"].post.requestBody.required == true) and
    (.paths["/api/v1/cv-cover-letter/generate"].post.requestBody.content["application/json"].schema["$ref"]
        == "#/components/schemas/GenerateRequest") and
    (.paths["/api/v1/cv-cover-letter/generate"].post.responses["200"].content["*/*"].schema["$ref"]
        == "#/components/schemas/GenerateCvCoverLetterResponse") and
    ($schemas.GenerateRequest.required
        | index("inputSchemaVersion") != null and
          index("profile") != null and index("job") != null) and
    ($schemas.GenerateRequest.properties.inputSchemaVersion.pattern
        == "(?:1|2)\\.0") and
    ($schemas.GenerateRequest.properties.evidenceSnapshots["$ref"]
        == "#/components/schemas/EvidenceSnapshotsInput") and
    ($schemas.EvidenceSnapshotInput.required
        | index("snapshotId") != null and index("purpose") != null and
          index("profileRevisionId") != null and
          index("profileContentDigest") != null and
          index("snapshotDigest") != null and index("sectionOrder") != null and
          index("selections") != null and index("createdAt") != null) and
    ($schemas.EvidenceSnapshotInput.properties.selections.maxItems == 50) and
    ($schemas.EvidenceSnapshotSelectionInput.required
        | index("entryId") != null and index("revisionId") != null and
          index("revisionNumber") != null and index("category") != null and
          index("contentDigest") != null and index("facts") != null) and
    ($schemas.EvidenceSnapshotSelectionInput.properties.facts.maxItems == 50) and
    ($schemas.EvidenceSnapshotFactInput.required
        | index("factId") != null and index("factType") != null and
          index("factValue") != null and index("numericClaim") != null) and
    ($schemas.ProfileInputSnapshot.required | index("provenance") != null) and
    ($schemas.ProfileInputSnapshot.properties.skills.maxItems == 40) and
    ($schemas.ProfileInputSnapshot.properties.skills.items.maxLength == 100) and
    ($schemas.ProfileInputSnapshot.properties.targetRoles.maxItems == 20) and
    ($schemas.ProfileInputSnapshot.properties.qualifications.maxItems == 30) and
    ($schemas.ProfileInputSnapshot.properties.employmentHistory.maxItems == 30) and
    ($schemas.ContactInputSnapshot.required | index("provenance") != null) and
    ($schemas.ContactInputSnapshot.properties.fullName.maxLength == 120) and
    ($schemas.ContactInputSnapshot.properties.email.maxLength == 254) and
    ($schemas.JobInputSnapshot.required
        | index("provenance") != null and index("title") != null and
          index("company") != null and index("description") != null) and
    ($schemas.JobInputSnapshot.properties.title.maxLength == 160) and
    ($schemas.JobInputSnapshot.properties.company.maxLength == 160) and
    ($schemas.JobInputSnapshot.properties.description.maxLength == 12000) and
    ($schemas.SnapshotProvenance.required
        | index("owner") != null and index("resourceId") != null and
          index("version") != null and index("capturedAt") != null) and
    ($schemas.SnapshotProvenance.properties.resourceId.maxLength == 128) and
    ($schemas.SnapshotProvenance.properties.version.maxLength == 128) and
    ($schemas.SnapshotProvenance.properties.owner.enum
        | index("AUTHENTICATION_SERVICE") != null and
          index("JOB_SERVICE") != null and
          index("USER_PROFILE_SERVICE") != null) and
    ($schemas.QualificationInput.properties.qualificationName.maxLength == 160) and
    ($schemas.QualificationInput.properties.dateAchieved.maxLength == 10) and
    ($schemas.EmploymentInput.properties.responsibilities.maxLength == 4000) and
    ($schemas.EmploymentInput.properties.startDate.maxLength == 10) and
    ($schemas.GenerateCvCoverLetterResponse.properties.inputSchemaVersion.type
        == "string") and
    ($schemas.GenerateCvCoverLetterResponse.properties.generationMetadata["$ref"]
        == "#/components/schemas/PromptGenerationMetadata") and
    ($schemas.GenerateCvCoverLetterResponse.properties.inputWarnings.items["$ref"]
        == "#/components/schemas/InputWarning") and
    ($schemas.PromptGenerationMetadata.required
        | index("releaseId") != null and
          index("bundleId") != null and
          index("bundleVersion") != null and
          index("bundleSha256") != null and
          index("templateVersion") != null and
          index("templateSha256") != null and
          index("rulesVersion") != null and
          index("rulesSha256") != null and
          index("schemaId") != null and
          index("schemaVersion") != null and
          index("schemaSha256") != null and
          index("evaluationPolicyVersion") != null and
          index("evaluationPolicySha256") != null) and
    ([
        $schemas.PromptGenerationMetadata.properties.bundleSha256.pattern,
        $schemas.PromptGenerationMetadata.properties.templateSha256.pattern,
        $schemas.PromptGenerationMetadata.properties.rulesSha256.pattern,
        $schemas.PromptGenerationMetadata.properties.schemaSha256.pattern,
        $schemas.PromptGenerationMetadata.properties.evaluationPolicySha256.pattern
    ] | all(. == "^[a-f0-9]{64}$")) and
    ($schemas.PromptGenerationMetadata.properties | has("prompt") | not) and
    ($schemas.PromptGenerationMetadata.properties | has("payload") | not) and
    (all($closed[]; $schemas[.].additionalProperties == false)) and
    ($schemas | has("UserProfile") | not) and
    ($schemas | has("Job") | not) and
    ($schemas | has("Aspirations") | not)
' "$contract" >/dev/null

echo "API contract policy: side-effect-free draft and legacy migration contracts are present and intact"
