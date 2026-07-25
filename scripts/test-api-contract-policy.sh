#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

copy_contract() {
    local destination="$1"
    mkdir -p "$destination"
    cp "$repository_root/contracts/openapi.json" \
       "$repository_root/contracts/SHA256SUMS" \
       "$destination/"
}

"$repository_root/scripts/verify-api-contract.sh" "$repository_root/contracts" >/dev/null

copy_contract "$temporary_dir/missing"
rm "$temporary_dir/missing/openapi.json"
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/missing" >/dev/null 2>&1; then
    echo "API contract policy negative test accepted a missing contract" >&2
    exit 1
fi

copy_contract "$temporary_dir/drift"
jq '.info.description = "unreviewed drift"' \
    "$temporary_dir/drift/openapi.json" \
    > "$temporary_dir/drift/changed.json"
mv "$temporary_dir/drift/changed.json" "$temporary_dir/drift/openapi.json"
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/drift" >/dev/null 2>&1; then
    echo "API contract policy negative test accepted checksum drift" >&2
    exit 1
fi

copy_contract "$temporary_dir/operation"
jq 'del(.paths["/api/v1/cv-cover-letter/generate"].post)' \
    "$temporary_dir/operation/openapi.json" \
    > "$temporary_dir/operation/changed.json"
mv "$temporary_dir/operation/changed.json" "$temporary_dir/operation/openapi.json"
(cd "$temporary_dir/operation" && sha256sum openapi.json > SHA256SUMS)
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/operation" >/dev/null 2>&1; then
    echo "API contract policy negative test accepted removal of generation" >&2
    exit 1
fi

copy_contract "$temporary_dir/request"
jq 'del(.components.schemas.GenerateRequest.required[] | select(. == "profile"))' \
    "$temporary_dir/request/openapi.json" \
    > "$temporary_dir/request/changed.json"
mv "$temporary_dir/request/changed.json" "$temporary_dir/request/openapi.json"
(cd "$temporary_dir/request" && sha256sum openapi.json > SHA256SUMS)
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/request" >/dev/null 2>&1; then
    echo "API contract policy negative test accepted removal of required profile input" >&2
    exit 1
fi

copy_contract "$temporary_dir/provenance"
jq 'del(.components.schemas.SnapshotProvenance.properties.owner.enum[]
        | select(. == "JOB_SERVICE"))' \
    "$temporary_dir/provenance/openapi.json" \
    > "$temporary_dir/provenance/changed.json"
mv "$temporary_dir/provenance/changed.json" "$temporary_dir/provenance/openapi.json"
(cd "$temporary_dir/provenance" && sha256sum openapi.json > SHA256SUMS)
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/provenance" >/dev/null 2>&1; then
    echo "API contract policy negative test accepted removal of Job Service provenance" >&2
    exit 1
fi

copy_contract "$temporary_dir/limit"
jq '.components.schemas.JobInputSnapshot.properties.description.maxLength = 12001' \
    "$temporary_dir/limit/openapi.json" \
    > "$temporary_dir/limit/changed.json"
mv "$temporary_dir/limit/changed.json" "$temporary_dir/limit/openapi.json"
(cd "$temporary_dir/limit" && sha256sum openapi.json > SHA256SUMS)
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/limit" >/dev/null 2>&1; then
    echo "API contract policy negative test accepted generation-input limit drift" >&2
    exit 1
fi

copy_contract "$temporary_dir/open-schema"
jq '.components.schemas.GenerateRequest.additionalProperties = true' \
    "$temporary_dir/open-schema/openapi.json" \
    > "$temporary_dir/open-schema/changed.json"
mv "$temporary_dir/open-schema/changed.json" "$temporary_dir/open-schema/openapi.json"
(cd "$temporary_dir/open-schema" && sha256sum openapi.json > SHA256SUMS)
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/open-schema" >/dev/null 2>&1; then
    echo "API contract policy negative test accepted unknown generation fields" >&2
    exit 1
fi

copy_contract "$temporary_dir/header"
jq 'del(.paths["/api/v1/cv-cover-letter/generate"].post.parameters)' \
    "$temporary_dir/header/openapi.json" \
    > "$temporary_dir/header/changed.json"
mv "$temporary_dir/header/changed.json" "$temporary_dir/header/openapi.json"
(cd "$temporary_dir/header" && sha256sum openapi.json > SHA256SUMS)
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/header" >/dev/null 2>&1; then
    echo "API contract policy negative test accepted removal of the trusted owner header" >&2
    exit 1
fi

copy_contract "$temporary_dir/service-identity"
jq 'del(
        .paths["/api/v1/cv-cover-letter/generate"].post.security,
        .components.securitySchemes.serviceToken
    )' \
    "$temporary_dir/service-identity/openapi.json" \
    > "$temporary_dir/service-identity/changed.json"
mv "$temporary_dir/service-identity/changed.json" \
   "$temporary_dir/service-identity/openapi.json"
(cd "$temporary_dir/service-identity" && sha256sum openapi.json > SHA256SUMS)
if "$repository_root/scripts/verify-api-contract.sh" "$temporary_dir/service-identity" >/dev/null 2>&1; then
    echo "API contract policy negative test accepted removal of Gateway authentication" >&2
    exit 1
fi

echo "API contract policy tests passed"
