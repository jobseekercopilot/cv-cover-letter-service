#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

copy_contracts() {
    local destination="$1"
    mkdir -p "$destination"
    cp "$repository_root"/src/main/openapi/*.json \
       "$repository_root"/src/main/openapi/*.SOURCE \
       "$repository_root/src/main/openapi/SHA256SUMS" \
       "$destination/"
}

refresh_manifest() {
    local directory="$1"
    (
        cd "$directory"
        sha256sum \
            application-tracker-service.json \
            document-store-service.json \
            llm-gateway.json \
            payment-service.json \
            > SHA256SUMS
    )
}

"$repository_root/scripts/verify-contracts.sh" "$repository_root/src/main/openapi" >/dev/null

copy_contracts "$temporary_dir/missing"
rm "$temporary_dir/missing/llm-gateway.json"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/missing" >/dev/null 2>&1; then
    echo "contract policy negative test accepted a missing producer contract" >&2
    exit 1
fi

copy_contracts "$temporary_dir/checksum-drift"
jq '.info.description = "unreviewed drift"' \
    "$temporary_dir/checksum-drift/document-store-service.json" \
    > "$temporary_dir/checksum-drift/changed.json"
mv "$temporary_dir/checksum-drift/changed.json" \
   "$temporary_dir/checksum-drift/document-store-service.json"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/checksum-drift" >/dev/null 2>&1; then
    echo "contract policy negative test accepted checksum drift" >&2
    exit 1
fi

copy_contracts "$temporary_dir/llm-operation"
jq 'del(.paths["/api/v2/generations"].post)' \
    "$temporary_dir/llm-operation/llm-gateway.json" \
    > "$temporary_dir/llm-operation/changed.json"
mv "$temporary_dir/llm-operation/changed.json" \
   "$temporary_dir/llm-operation/llm-gateway.json"
refresh_manifest "$temporary_dir/llm-operation"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/llm-operation" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of LLM v2 generation" >&2
    exit 1
fi

copy_contracts "$temporary_dir/llm-trust-boundary"
jq '.components.schemas.GenerationRequest.required -= ["trustedInstructions"]' \
    "$temporary_dir/llm-trust-boundary/llm-gateway.json" \
    > "$temporary_dir/llm-trust-boundary/changed.json"
mv "$temporary_dir/llm-trust-boundary/changed.json" \
   "$temporary_dir/llm-trust-boundary/llm-gateway.json"
refresh_manifest "$temporary_dir/llm-trust-boundary"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/llm-trust-boundary" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of the trusted instruction boundary" >&2
    exit 1
fi

copy_contracts "$temporary_dir/llm-audit"
jq 'del(.components.schemas.GenerationResponse.properties.audit)' \
    "$temporary_dir/llm-audit/llm-gateway.json" \
    > "$temporary_dir/llm-audit/changed.json"
mv "$temporary_dir/llm-audit/changed.json" \
   "$temporary_dir/llm-audit/llm-gateway.json"
refresh_manifest "$temporary_dir/llm-audit"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/llm-audit" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of LLM model/cost audit metadata" >&2
    exit 1
fi

copy_contracts "$temporary_dir/document-field"
jq 'del(.components.schemas.CreateDocumentRequest.properties.content)' \
    "$temporary_dir/document-field/document-store-service.json" \
    > "$temporary_dir/document-field/changed.json"
mv "$temporary_dir/document-field/changed.json" \
   "$temporary_dir/document-field/document-store-service.json"
refresh_manifest "$temporary_dir/document-field"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/document-field" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of document content" >&2
    exit 1
fi

copy_contracts "$temporary_dir/document-security"
jq 'del(.components.securitySchemes.serviceToken)' \
    "$temporary_dir/document-security/document-store-service.json" \
    > "$temporary_dir/document-security/changed.json"
mv "$temporary_dir/document-security/changed.json" \
   "$temporary_dir/document-security/document-store-service.json"
refresh_manifest "$temporary_dir/document-security"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/document-security" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of Store service identity" >&2
    exit 1
fi

copy_contracts "$temporary_dir/document-owner"
jq '.paths["/api/v1/documents"].post.parameters = []' \
    "$temporary_dir/document-owner/document-store-service.json" \
    > "$temporary_dir/document-owner/changed.json"
mv "$temporary_dir/document-owner/changed.json" \
   "$temporary_dir/document-owner/document-store-service.json"
refresh_manifest "$temporary_dir/document-owner"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/document-owner" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of Store owner context" >&2
    exit 1
fi

copy_contracts "$temporary_dir/tracker-security"
jq 'del(.components.securitySchemes.serviceToken)' \
    "$temporary_dir/tracker-security/application-tracker-service.json" \
    > "$temporary_dir/tracker-security/changed.json"
mv "$temporary_dir/tracker-security/changed.json" \
   "$temporary_dir/tracker-security/application-tracker-service.json"
refresh_manifest "$temporary_dir/tracker-security"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/tracker-security" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of tracker service identity" >&2
    exit 1
fi

copy_contracts "$temporary_dir/payment-operation"
jq 'del(.paths["/api/v1/payments/reservations/{reservationId}/commit"].post)' \
    "$temporary_dir/payment-operation/payment-service.json" \
    > "$temporary_dir/payment-operation/changed.json"
mv "$temporary_dir/payment-operation/changed.json" \
   "$temporary_dir/payment-operation/payment-service.json"
refresh_manifest "$temporary_dir/payment-operation"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/payment-operation" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of payment commit" >&2
    exit 1
fi

copy_contracts "$temporary_dir/source-revision"
sed 's/revision=d9e6bc9/revision=0000000/' \
    "$temporary_dir/source-revision/application-tracker-service.SOURCE" \
    > "$temporary_dir/source-revision/changed.SOURCE"
mv "$temporary_dir/source-revision/changed.SOURCE" \
   "$temporary_dir/source-revision/application-tracker-service.SOURCE"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/source-revision" >/dev/null 2>&1; then
    echo "contract policy negative test accepted unreviewed producer revision metadata" >&2
    exit 1
fi

echo "contract policy tests passed"
