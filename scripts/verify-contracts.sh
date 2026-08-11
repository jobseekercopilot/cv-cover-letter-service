#!/usr/bin/env bash
set -euo pipefail

contract_dir="${1:-src/main/openapi}"
manifest="$contract_dir/SHA256SUMS"
contract_names=(
    application-tracker-service
    document-store-service
    llm-gateway
    payment-service
)

for contract_name in "${contract_names[@]}"; do
    for required_file in \
        "$contract_dir/$contract_name.json" \
        "$contract_dir/$contract_name.SOURCE"; do
        if [[ ! -f "$required_file" || -L "$required_file" ]]; then
            echo "contract policy: required regular file is missing or is a symlink: $required_file" >&2
            exit 1
        fi
    done
done

if [[ ! -f "$manifest" || -L "$manifest" ]]; then
    echo "contract policy: required regular file is missing or is a symlink: $manifest" >&2
    exit 1
fi

(
    cd "$contract_dir"
    sha256sum --check --strict SHA256SUMS
)

verify_source() {
    local name="$1"
    local repository="$2"
    local revision="$3"
    local sha256="$4"
    local source_metadata="$contract_dir/$name.SOURCE"

    test "$(wc -l < "$source_metadata" | tr -d ' ')" = 4
    grep -Fx "repository=$repository" "$source_metadata" >/dev/null
    grep -Fx "revision=$revision" "$source_metadata" >/dev/null
    grep -Fx 'path=contracts/openapi.json' "$source_metadata" >/dev/null
    grep -Fx "sha256=$sha256" "$source_metadata" >/dev/null
}

verify_source \
    application-tracker-service \
    jobseekercopilot/application-tracker-service \
    d9e6bc9fcbe4ef665334c58672c8062b1e4796aa \
    549cebba300c2caf3403b9de01d3c34de84464a280a02183751e8a9583ad982a
verify_source \
    document-store-service \
    jobseekercopilot/document-store-service \
    b696fe81e9b900e0749e185f595ff4c98c24119d \
    3d0595c83cc66d9037e08af6a4b087c115c9a5d99ec71491f1aa5fc3afffd6ba
verify_source \
    llm-gateway \
    jobseekercopilot/llm-gateway \
    1f633d616bebb981579153fcf87850750891942a \
    21c9f5f0ad57c0f4af4d57e51719c3836de5a6d2dc3bada26b174b036df31fda
verify_source \
    payment-service \
    jobseekercopilot/payment-service \
    2fc961c8facc89a334b051e155836423912a2498 \
    08312957171b34df832b5b3e62ffba93d68007981ac7c284e8b0bff7de22295a

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "2.0.0") and
    (.paths["/api/v2/generations"].post.operationId == "generateV2") and
    (.paths["/api/v2/generations"].post.requestBody.content["application/json"].schema["$ref"]
        == "#/components/schemas/GenerationRequest") and
    (.components.schemas.GenerationRequest.required
        | index("contractVersion") != null and
          index("task") != null and
          index("trustedInstructions") != null and
          index("untrustedInput") != null and
          index("output") != null and
          index("limits") != null) and
    (.components.schemas.GenerationRequest.additionalProperties == false) and
    (.components.schemas.GenerationRequest.properties.contractVersion.enum
        | index("2.0") != null) and
    (.components.schemas.GenerationRequest.properties.trustedInstructions.maxLength == 12000) and
    (.components.schemas.GenerationRequest.properties.untrustedInput.maxLength == 170000) and
    (.components.schemas.GenerationOutputContract.required | index("format") != null) and
    (.components.schemas.GenerationOutputContract.properties.format.enum
        | index("JSON_SCHEMA") != null) and
    (.components.schemas.GenerationOutputContract.properties | has("jsonSchema")) and
    (.components.schemas.GenerationLimits.properties.maxOutputTokens.maximum == 32768) and
    (.components.schemas.GenerationLimits.properties.temperature.maximum == 1) and
    (.components.schemas.GenerationResponse.properties
        | has("output") and has("finishReason") and has("usage") and
          has("schemaId") and has("schemaVersion") and has("audit")) and
    (.components.schemas.GenerationResponse.required | index("audit") != null) and
    (.components.schemas.GenerationResponse.properties.audit["$ref"]
        == "#/components/schemas/GenerationAudit") and
    (.components.schemas.GenerationAudit.additionalProperties == false) and
    ((.components.schemas.GenerationAudit.required | sort)
        == ["admissionPolicyVersion", "currency", "estimatedCostMicroUsd",
            "estimatedInputTokensAtAdmission", "modelDeploymentVersion",
            "modelId", "pricingVersion"]) and
    ((.components.schemas.GenerationAudit.properties | keys)
        == ["admissionPolicyVersion", "currency", "estimatedCostMicroUsd",
            "estimatedInputTokensAtAdmission", "modelDeploymentVersion",
            "modelId", "pricingVersion"]) and
    (.components.schemas.GenerationAudit.properties | has("provider") | not) and
    (.components.schemas.GenerationUsage.properties
        | has("inputTokens") and has("outputTokens") and has("totalTokens"))
' "$contract_dir/llm-gateway.json" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "1.1.0") and
    (.paths["/api/v1/documents"].post.operationId == "createDocument") and
    (.paths["/api/v1/documents"].post.security
        | any(has("serviceToken"))) and
    (.paths["/api/v1/documents"].post.parameters
        | any(.name == "X-Document-Owner" and .in == "header")) and
    (.components.securitySchemes.serviceToken.type == "apiKey") and
    (.components.securitySchemes.serviceToken.name == "X-Service-Token") and
    (.components.schemas.CreateDocumentRequest.required
        | index("userId") != null and index("jobId") != null and
          index("documentType") != null and index("title") != null and
          index("content") != null) and
    (.components.schemas.CreateDocumentRequest.properties
        | has("userId") and has("jobId") and has("documentType") and
          has("title") and has("content")) and
    (.components.schemas.CreateDocumentRequest.properties.documentType.enum
        | index("CV") != null and index("COVER_LETTER") != null) and
    (.components.schemas.GeneratedDocumentResponse.properties | has("id"))
' "$contract_dir/document-store-service.json" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "1.1.0") and
    (.paths["/api/v1/applications"].post.operationId == "createApplication") and
    (.paths["/api/v1/applications"].post.security
        | any(has("serviceToken"))) and
    (.components.securitySchemes.serviceToken.type == "apiKey") and
    (.components.securitySchemes.serviceToken.name == "X-Service-Token") and
    (.components.schemas.CreateApplicationRequest.required
        | index("userId") != null and index("jobId") != null and
          index("jobTitle") != null and index("companyName") != null and
          index("cvDocumentId") != null and index("coverLetterDocumentId") != null) and
    (.components.schemas.ApplicationRecordResponse.properties | has("id"))
' "$contract_dir/application-tracker-service.json" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "3.0.0") and
    (.components.securitySchemes.serviceToken
        | .type == "apiKey" and .in == "header" and .name == "X-Service-Token") and
    (.paths["/api/v1/payments/reservations"].post.operationId == "createReservation") and
    (.paths["/api/v1/payments/reservations/{reservationId}"].get.operationId
        == "reservationStatus") and
    (.paths["/api/v1/payments/reservations/{reservationId}/commit"].post.operationId
        == "commitReservation") and
    (.paths["/api/v1/payments/reservations/{reservationId}/release"].post.operationId
        == "releaseReservation") and
    ([.paths["/api/v1/payments/reservations"].post,
      .paths["/api/v1/payments/reservations/{reservationId}"].get,
      .paths["/api/v1/payments/reservations/{reservationId}/commit"].post,
      .paths["/api/v1/payments/reservations/{reservationId}/release"].post]
        | all(
            .security == [{"serviceToken": []}] and
            (.parameters
                | any(.name == "X-Payment-Owner" and .in == "header" and .required == true)) and
            (.parameters | all(.name != "X-User-Id")))) and
    (.components.schemas.CreateReservationRequest.required
        | index("feature") != null and index("operationKey") != null) and
    (.components.schemas.CreateReservationRequest.properties
        | has("estimatedTokens") and has("operationKey") and
          has("referenceType") and has("referenceId")) and
    (.components.schemas.ReservationResponse.properties
        | has("reservationId") and has("reservedTokens") and
          has("balanceAfterReservation") and has("status") and has("expiresAt")) and
    (.components.schemas.ReservationStatusResponse.properties
        | has("reservationId") and has("operationKey") and has("status") and
          has("expiresAt") and has("lastTransitionAt") and
          has("lastTransitionReason") and has("reconciliationAttempts") and
          has("reconciliationErrorCode")) and
    (.components.schemas.CommitReservationRequest.required | index("actualTokens") != null) and
    (.components.schemas.CommitReservationRequest.properties
        | has("provider") and has("model") and has("inputTokens") and
          has("outputTokens") and has("description")) and
    (.components.schemas.ReleaseReservationRequest.properties | has("reason"))
' "$contract_dir/payment-service.json" >/dev/null

echo "contract policy: all pinned producer sources are present, intact and compatible"
