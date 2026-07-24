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
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "2.0.0") and
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
    (.components.schemas.GenerateRequest.required
        | index("job") != null and index("userProfile") != null) and
    (.components.schemas.Job.required
        | index("id") != null and index("title") != null and
          index("company") != null and index("description") != null) and
    (.components.schemas.UserProfile.required | index("userId") != null) and
    (.components.schemas.Aspirations.properties.targetWeeklyHours.enum
        | index("FULL_TIME") != null and index("PART_TIME_16_30") != null and
          index("PART_TIME_UNDER_16") != null and index("FLEXIBLE") != null)
' "$contract" >/dev/null

echo "API contract policy: authenticated CV and Cover Letter OpenAPI source is present and intact"
