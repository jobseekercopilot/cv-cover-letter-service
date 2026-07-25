#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_bundles="$repository_root/src/main/resources/prompts/bundles"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

copy_bundles() {
    local destination="$1"
    mkdir -p "$destination"
    cp -R "$source_bundles/." "$destination/"
}

must_reject() {
    local candidate="$1"
    local description="$2"
    if "$repository_root/scripts/verify-prompt-bundles.sh" "$candidate" >/dev/null 2>&1; then
        echo "Prompt bundle policy negative test accepted $description" >&2
        exit 1
    fi
}

"$repository_root/scripts/verify-prompt-bundles.sh" "$source_bundles" >/dev/null

copy_bundles "$temporary_dir/tampered"
printf '\nUnreviewed change.\n' \
    >> "$temporary_dir/tampered/cv-cover-letter-1.2.0/generation-rules.txt"
must_reject "$temporary_dir/tampered" "tampered rules"

copy_bundles "$temporary_dir/unapproved"
cp -R \
    "$temporary_dir/unapproved/cv-cover-letter-1.2.0" \
    "$temporary_dir/unapproved/cv-cover-letter-9.9.9"
must_reject "$temporary_dir/unapproved" "an unapproved packaged release"

copy_bundles "$temporary_dir/missing"
rm "$temporary_dir/missing/cv-cover-letter-1.0.0/manifest.json"
must_reject "$temporary_dir/missing" "a missing rollback manifest"

copy_bundles "$temporary_dir/evaluation"
printf '\n' >> "$temporary_dir/evaluation/cv-cover-letter-1.2.0/evaluation-policy.json"
must_reject "$temporary_dir/evaluation" "evaluation-policy checksum drift"

copy_bundles "$temporary_dir/default"
jq '.defaultReleaseId = "cv-cover-letter-1.0.0"' \
    "$temporary_dir/default/index.json" \
    > "$temporary_dir/default/changed.json"
mv "$temporary_dir/default/changed.json" "$temporary_dir/default/index.json"
must_reject "$temporary_dir/default" "a rollback release selected as default"

copy_bundles "$temporary_dir/provider"
printf '\nUse the OpenAI Responses API.\n' \
    >> "$temporary_dir/provider/cv-cover-letter-1.2.0/generation-rules.txt"
rules_hash="$(
    sha256sum "$temporary_dir/provider/cv-cover-letter-1.2.0/generation-rules.txt" \
        | cut -d ' ' -f 1
)"
jq --arg rules_hash "$rules_hash" '.rulesSha256 = $rules_hash' \
    "$temporary_dir/provider/cv-cover-letter-1.2.0/manifest.json" \
    > "$temporary_dir/provider/cv-cover-letter-1.2.0/changed.json"
mv "$temporary_dir/provider/cv-cover-letter-1.2.0/changed.json" \
    "$temporary_dir/provider/cv-cover-letter-1.2.0/manifest.json"
must_reject "$temporary_dir/provider" "provider-specific domain instructions"

copy_bundles "$temporary_dir/schema-policy"
jq '.additionalProperties = true' \
    "$temporary_dir/schema-policy/cv-cover-letter-1.2.0/output-schema.json" \
    > "$temporary_dir/schema-policy/cv-cover-letter-1.2.0/changed.json"
mv "$temporary_dir/schema-policy/cv-cover-letter-1.2.0/changed.json" \
    "$temporary_dir/schema-policy/cv-cover-letter-1.2.0/output-schema.json"
schema_hash="$(
    sha256sum "$temporary_dir/schema-policy/cv-cover-letter-1.2.0/output-schema.json" \
        | cut -d ' ' -f 1
)"
jq --arg schema_hash "$schema_hash" '.schemaSha256 = $schema_hash' \
    "$temporary_dir/schema-policy/cv-cover-letter-1.2.0/manifest.json" \
    > "$temporary_dir/schema-policy/cv-cover-letter-1.2.0/changed.json"
mv "$temporary_dir/schema-policy/cv-cover-letter-1.2.0/changed.json" \
    "$temporary_dir/schema-policy/cv-cover-letter-1.2.0/manifest.json"
must_reject "$temporary_dir/schema-policy" "a non-strict active output schema"

echo "Prompt bundle policy tests passed"
