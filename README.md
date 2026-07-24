# CV and Cover Letter Service

Domain service that builds the CV/cover-letter prompt, requests generation from
`llm-gateway`, validates and renders the response, stores both documents, and
creates an application record.

This service is **not beta-ready**. Its build is reproducible from committed
source, but generated claims are not traceable to source facts, the prompt is
vulnerable to instructions in untrusted content, and documents are persisted
before user approval. See
[`docs/BETA_READINESS_AUDIT.md`](docs/BETA_READINESS_AUDIT.md).

## Technology

- Java 17
- Spring Boot 3.2.0
- Maven

## API contract

[`contracts/openapi.json`](contracts/openapi.json) is the producer-owned
OpenAPI source. The immutable Java client version and reviewed source revision
are recorded in [`api/client-release.json`](api/client-release.json). Generated
client source and packages are disposable build output and are never committed.
See [`api/README.md`](api/README.md) for the release and compatibility policy.

## Gateway identity boundary

The generation endpoint accepts calls only from the Document Generation
Gateway. Configure `CV_COVER_LETTER_GATEWAY_TOKEN` with a dedicated secret of
at least 32 bytes. The Gateway must send that credential once in
`X-Service-Token` and its authenticated user's stable subject once in
`X-Document-Owner`. Caller-controlled identity in the request body or the
obsolete `X-User-Id` header never selects the owner.

See [`docs/AUTHORIZATION_BOUNDARY.md`](docs/AUTHORIZATION_BOUNDARY.md) for the
failure contract, rotation guidance and remaining downstream boundaries.

The LLM Gateway, Document Store and Application Tracker clients are generated
during Maven `generate-sources` from reviewed, checksum-protected producer
contracts under `src/main/openapi`. The raw Payment reserve/commit/release
adapter is checked against its pinned producer contract. Generated sources and
binaries are build outputs and are not committed. See
[`docs/CONTRACT_GOVERNANCE.md`](docs/CONTRACT_GOVERNANCE.md).

## Build

```bash
./scripts/test-contract-policy.sh
./scripts/verify-contracts.sh
./scripts/test-api-contract-policy.sh
./scripts/verify-api-contract.sh
python3 scripts/verify_client_release.py
python3 scripts/test_openapi_breaking.py
./scripts/test-client-generation.sh
mvn -B --no-transfer-progress clean verify
docker build --tag local/cv-cover-letter-service .
```

These commands are the clean-clone verification contract. They require no
sibling repository, local `libs/` directory, generated JAR or preinstalled
Job Seeker Copilot artifact. Tests use mocks and local application endpoints;
they make no live or paid model request.

## Safe local use

Use synthetic fixtures and the deterministic provider mode. Do not use real
CVs, cover letters, profiles, job-seeker data, model credentials, or paid model
requests while this service remains pre-beta.

## Licence

Copyright © 2026 Bernard McGeever. All rights reserved.

This repository contains proprietary software belonging to Bernard McGeever.
It may not be used, copied, modified or distributed without express written
permission. See [LICENSE](./LICENSE).
