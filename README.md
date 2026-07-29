# CV and Cover Letter Service

Domain service that builds the CV/cover-letter prompt, requests generation from
`llm-gateway`, validates the response, and returns bounded rendered drafts plus
model-usage evidence. The additive draft API has no payment, document, export,
approval, or application side effects. The legacy generate-and-commit endpoint
remains temporarily available for a coordinated Gateway migration.

This service is **not beta-ready**. Its build is reproducible from committed
source, its prompt releases are immutable and rollback-capable, and generated
claims now fail closed against approved source facts. The Gateway must adopt
the pure draft boundary and complete its durable approval workflow before the
legacy endpoint and downstream credentials can be removed. See
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

Contract `3.4.0` accepts schema `2.0` immutable evidence snapshots for the
durable Gateway flow. CV and cover-letter snapshots are validated independently
against the exact profile revision, preserve claimant-selected entry and section
order, and expose only stable confirmed fact IDs to generation. The claim
ledger rejects positional profile IDs, cross-purpose evidence, unknown or
duplicate facts, unsupported numeric or sensitive claims, and unaccounted final
content. The draft response now exposes the exact bounded validated claim
ledger with a stable ID and SHA-256 digest for downstream document provenance.
Schema `1.0` remains available only for the coordinated legacy migration.

Generation accepts only the versioned, bounded profile and canonical-job
snapshots documented in
[`docs/GENERATION_INPUT_CONTRACT.md`](docs/GENERATION_INPUT_CONTRACT.md).
Unknown fields and invalid provenance fail closed; deterministic warnings
report missing, normalised, duplicate, or conflicting evidence. Contact
details remain render-only and are not sent to the model.

`POST /api/v1/cv-cover-letter/drafts/estimate` performs validation and returns
the conservative reservation size without calling a provider.
`POST /api/v1/cv-cover-letter/drafts` requires the Gateway's durable
`X-Generation-Operation-Id` and returns draft content, usage, and model audit
evidence. See
[`docs/DRAFT_GENERATION_BOUNDARY.md`](docs/DRAFT_GENERATION_BOUNDARY.md).

## Prompt releases

Prompt bundle `cv-cover-letter-1.5.1` and output schema `3.2.0` render projects
as projects, keep paid employment separate, cap the CV at 8–12 unique confirmed
skills where available, omit unsupported empty sections, and use the correct
UK generic greeting/sign-off pair. A post-grounding quality policy rejects
repeated narrative, repeated qualifications, missing selected evidence and
project fields assembled from different evidence entries. Release `1.5.1`
also requires an explicit complete claim-path audit for every non-empty final
field, including generic narrative and dynamic array indexes. The immutable
`1.5.0` release remains packaged as the immediate emergency rollback.

Prompt template, rules, output schema and evaluation-policy versions are
selected as one reviewed bundle. Checksums, an approved-release index and
negative policy tests reject unreviewed drift at startup and in CI. Generation
responses and structured logs expose non-PII release metadata; prompt or source
payloads are never placed in metadata.

See [`docs/PROMPT_GOVERNANCE.md`](docs/PROMPT_GOVERNANCE.md) for the change,
evaluation and rollback procedure. Durable attachment of the same provenance
to an immutable stored document version remains a beta dependency on DOC-06.
The runtime uses LLM Gateway v2 to keep reviewed instructions, untrusted job
and profile evidence, and the strict output schema in separate fields. The
same bounded schema is enforced again before rendering or persistence; see
[`docs/MODEL_OUTPUT_CONTRACT.md`](docs/MODEL_OUTPUT_CONTRACT.md). The threat
model and incident procedure are in
[`docs/PROMPT_INJECTION_THREAT_MODEL.md`](docs/PROMPT_INJECTION_THREAT_MODEL.md).
The evidence catalogue, claim dispositions and hallucination checks are in
[`docs/CLAIM_EVIDENCE_POLICY.md`](docs/CLAIM_EVIDENCE_POLICY.md).

## Gateway identity boundary

The generation endpoint accepts calls only from the Document Generation
Gateway. Configure `CV_COVER_LETTER_GATEWAY_TOKEN` with a dedicated secret of
at least 32 bytes. The Gateway must send that credential once in
`X-Service-Token` and its authenticated user's stable subject once in
`X-Document-Owner`. Caller-controlled identity in the request body or the
obsolete `X-User-Id` header never selects the owner.

See [`docs/AUTHORIZATION_BOUNDARY.md`](docs/AUTHORIZATION_BOUNDARY.md) for the
failure contract and rotation guidance. Configure distinct
`DOCUMENT_STORE_PRODUCER_TOKEN`, `APPLICATION_TRACKER_PRODUCER_TOKEN`, and
`CV_COVER_LETTER_TO_PAYMENT_SERVICE_TOKEN` runtime secrets (at least 32 bytes
each) for authenticated owner-bound downstream writes. Fleet runtime/E2E
evidence remains an open CVCL-02 dependency.

The LLM Gateway v2, Document Store and Application Tracker clients are generated
during Maven `generate-sources` from reviewed, checksum-protected producer
contracts under `src/main/openapi`. The raw Payment
reserve/commit/release/lifecycle adapter is checked against its pinned 3.0.0
producer contract. It retries one stable operation key per invocation, resolves
ambiguous terminal responses and surfaces unresolved compensation. Generated
sources and binaries are build outputs and are not committed. See
[`docs/CONTRACT_GOVERNANCE.md`](docs/CONTRACT_GOVERNANCE.md).

## Build

```bash
./scripts/test-contract-policy.sh
./scripts/verify-contracts.sh
./scripts/test-prompt-bundle-policy.sh
./scripts/verify-prompt-bundles.sh
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
