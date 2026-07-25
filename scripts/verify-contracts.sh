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
    fedcdbdec63795269c4e4c4f43fc32f38c6327b1 \
    410ab1a7a2e8a5a5ad374443ec834f6aef7f778f6936b3ef90c33f8e580cdbd9
verify_source \
    llm-gateway \
    jobseekercopilot/llm-gateway \
    0e2cf79a5fc231971aabdd96e2d1117f84c5c9c0 \
    6557c9f3d1c216fdd12d2fd157ca79b82356c00131ac48c62cef4ea441cedd1d
verify_source \
    payment-service \
    jobseekercopilot/payment-service \
    3175e5730cd0743e15455a0acc8e2bc35b56a78f \
    2b1bfef95e1ba4c1f191627dfc4972b3ed7a931dead8fbb7aecbf5b793acae7a

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
    (.components.schemas.GenerationRequest.properties.untrustedInput.maxLength == 40000) and
    (.components.schemas.GenerationOutputContract.required | index("format") != null) and
    (.components.schemas.GenerationOutputContract.properties.format.enum
        | index("JSON_SCHEMA") != null) and
    (.components.schemas.GenerationOutputContract.properties | has("jsonSchema")) and
    (.components.schemas.GenerationLimits.properties.maxOutputTokens.maximum == 4096) and
    (.components.schemas.GenerationLimits.properties.temperature.maximum == 1) and
    (.components.schemas.GenerationResponse.properties
        | has("output") and has("finishReason") and has("usage") and
          has("schemaId") and has("schemaVersion")) and
    (.components.schemas.GenerationUsage.properties
        | has("inputTokens") and has("outputTokens") and has("totalTokens"))
' "$contract_dir/llm-gateway.json" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "1.0.0") and
    (.paths["/api/v1/documents"].post.operationId == "createDocument") and
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
    (.info.version == "1.0.0") and
    (.paths["/api/v1/payments/reservations"].post.operationId == "createReservation") and
    (.paths["/api/v1/payments/reservations/{reservationId}/commit"].post.operationId
        == "commitReservation") and
    (.paths["/api/v1/payments/reservations/{reservationId}/release"].post.operationId
        == "releaseReservation") and
    ([.paths["/api/v1/payments/reservations"].post,
      .paths["/api/v1/payments/reservations/{reservationId}/commit"].post,
      .paths["/api/v1/payments/reservations/{reservationId}/release"].post]
        | all(.parameters | any(.name == "X-User-Id" and .in == "header" and .required == true))) and
    (.components.schemas.CreateReservationRequest.required | index("feature") != null) and
    (.components.schemas.CreateReservationRequest.properties
        | has("estimatedTokens") and has("referenceType") and has("referenceId")) and
    (.components.schemas.ReservationResponse.properties
        | has("reservationId") and has("reservedTokens") and
          has("balanceAfterReservation") and has("status")) and
    (.components.schemas.CommitReservationRequest.required | index("actualTokens") != null) and
    (.components.schemas.CommitReservationRequest.properties
        | has("provider") and has("model") and has("inputTokens") and
          has("outputTokens") and has("description")) and
    (.components.schemas.ReleaseReservationRequest.properties | has("reason"))
' "$contract_dir/payment-service.json" >/dev/null

echo "contract policy: all pinned producer sources are present, intact and compatible"
