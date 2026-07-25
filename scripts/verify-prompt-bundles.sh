#!/usr/bin/env bash
set -euo pipefail

bundle_root="${1:-src/main/resources/prompts/bundles}"
index="$bundle_root/index.json"

fail() {
    echo "Prompt bundle policy failed: $1" >&2
    exit 1
}

[[ -d "$bundle_root" && ! -L "$bundle_root" ]] \
    || fail "bundle root must be a regular directory: $bundle_root"
[[ -f "$index" && ! -L "$index" ]] \
    || fail "index must be a regular file: $index"
[[ -z "$(find "$bundle_root" -type l -print -quit)" ]] \
    || fail "symlinks are forbidden below the bundle root"

jq -e '
    . as $index |
    type == "object" and
    ((keys | sort) == ["approvedReleaseIds", "defaultReleaseId"]) and
    (.defaultReleaseId | type == "string") and
    (.approvedReleaseIds | type == "array" and length > 0) and
    ([.approvedReleaseIds[] | type == "string"] | all) and
    ((.approvedReleaseIds | unique | length) == (.approvedReleaseIds | length)) and
    ($index.approvedReleaseIds | index($index.defaultReleaseId) != null)
' "$index" >/dev/null || fail "index shape, default or approved release list is invalid"

default_release="$(jq -r '.defaultReleaseId' "$index")"
mapfile -t approved_releases < <(jq -r '.approvedReleaseIds[]' "$index")
active_count=0

for release_id in "${approved_releases[@]}"; do
    [[ "$release_id" =~ ^[a-z0-9][a-z0-9.-]{2,63}$ ]] \
        || fail "invalid release ID: $release_id"
    release_dir="$bundle_root/$release_id"
    manifest="$release_dir/manifest.json"
    template="$release_dir/cv-cover-letter-prompt-template.txt"
    rules="$release_dir/generation-rules.txt"
    schema="$release_dir/output-schema.json"
    evaluation_policy="$release_dir/evaluation-policy.json"

    [[ -d "$release_dir" && ! -L "$release_dir" ]] \
        || fail "approved release directory is missing: $release_id"
    for required_file in "$manifest" "$template" "$rules" "$schema" "$evaluation_policy"; do
        [[ -f "$required_file" && ! -L "$required_file" ]] \
            || fail "required regular file is missing: $required_file"
    done
    [[ "$(find "$release_dir" -maxdepth 1 -type f | wc -l)" -eq 5 ]] \
        || fail "release contains files outside the reviewed five-file bundle: $release_id"

    jq -e --arg release_id "$release_id" '
        type == "object" and
        ((keys | sort) == [
            "bundleId",
            "bundleVersion",
            "evaluationPolicySha256",
            "evaluationPolicyVersion",
            "releaseId",
            "releaseStatus",
            "rulesSha256",
            "rulesVersion",
            "schemaId",
            "schemaSha256",
            "schemaVersion",
            "templateSha256",
            "templateVersion"
        ]) and
        (.releaseId == $release_id) and
        (.bundleId | type == "string" and length > 0) and
        ([.bundleVersion, .templateVersion, .rulesVersion, .schemaVersion,
          .evaluationPolicyVersion]
            | all(type == "string" and test("^[0-9]+\\.[0-9]+\\.[0-9]+$"))) and
        (.schemaId | type == "string" and length > 0) and
        (.releaseStatus == "ACTIVE" or .releaseStatus == "ROLLBACK") and
        ([.templateSha256, .rulesSha256, .schemaSha256, .evaluationPolicySha256]
            | all(type == "string" and test("^[a-f0-9]{64}$")))
    ' "$manifest" >/dev/null || fail "manifest is invalid: $release_id"

    status="$(jq -r '.releaseStatus' "$manifest")"
    if [[ "$status" == "ACTIVE" ]]; then
        active_count=$((active_count + 1))
    fi
    if [[ "$release_id" == "$default_release" && "$status" != "ACTIVE" ]]; then
        fail "default release must have ACTIVE status"
    fi

    expected_template="$(jq -r '.templateSha256' "$manifest")"
    expected_rules="$(jq -r '.rulesSha256' "$manifest")"
    expected_schema="$(jq -r '.schemaSha256' "$manifest")"
    expected_evaluation_policy="$(jq -r '.evaluationPolicySha256' "$manifest")"
    [[ "$(sha256sum "$template" | cut -d ' ' -f 1)" == "$expected_template" ]] \
        || fail "template checksum mismatch: $release_id"
    [[ "$(sha256sum "$rules" | cut -d ' ' -f 1)" == "$expected_rules" ]] \
        || fail "rules checksum mismatch: $release_id"
    [[ "$(sha256sum "$schema" | cut -d ' ' -f 1)" == "$expected_schema" ]] \
        || fail "schema checksum mismatch: $release_id"
    [[ "$(sha256sum "$evaluation_policy" | cut -d ' ' -f 1)" == "$expected_evaluation_policy" ]] \
        || fail "evaluation policy checksum mismatch: $release_id"

    jq -e 'type == "object"' "$schema" >/dev/null \
        || fail "output schema must be a JSON object: $release_id"
    evaluation_policy_version="$(jq -r '.evaluationPolicyVersion' "$manifest")"
    jq -e --arg version "$evaluation_policy_version" '
        type == "object" and .policyVersion == $version
    ' "$evaluation_policy" >/dev/null \
        || fail "evaluation policy must be a matching versioned JSON object: $release_id"
    for placeholder in \
        '{{LANGUAGE}}' \
        '{{RULES}}' \
        '{{OUTPUT_SCHEMA_JSON}}' \
        '{{PROFILE_INPUT_JSON}}' \
        '{{JOB_INPUT_JSON}}' \
        '{{INPUT_WARNINGS_JSON}}'; do
        grep -Fq "$placeholder" "$template" \
            || fail "template is missing $placeholder: $release_id"
    done
    if grep -Eiq '\b(openai|anthropic|gemini|chatgpt|chat completions|responses api)\b' \
        "$template" "$rules"; then
        fail "provider-specific instruction found in domain bundle: $release_id"
    fi
done

[[ "$active_count" -eq 1 ]] || fail "exactly one approved release must be ACTIVE"
mapfile -t packaged_releases < <(
    find "$bundle_root" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort
)
mapfile -t sorted_approved < <(printf '%s\n' "${approved_releases[@]}" | sort)
[[ "${packaged_releases[*]}" == "${sorted_approved[*]}" ]] \
    || fail "packaged release directories must exactly match the approved index"

echo "Prompt bundle policy passed: ${#approved_releases[@]} reviewed releases; default=$default_release"
