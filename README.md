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

Prompt bundle `cv-cover-letter-1.5.8`, output schema `3.8.0`, evaluation policy
`1.5.6` and parser `3.5.0` render projects as projects, keep paid employment
separate, select at most 12 exact job-relevant CV skills without a minimum or
quota, omit unsupported empty sections, and use the correct UK generic
greeting/sign-off pair. Claim policy `2.13.0` and post-grounding quality policy
`1.2.0` reject unsupported skills, literal cover-letter skill lists, repeated
narrative, repeated qualifications, missing selected evidence and project
fields assembled from different evidence entries. Release `1.5.1` requires an explicit complete
claim-path audit for every non-empty final field, including generic narrative
and dynamic array indexes. Release `1.5.2` narrows the provider output ledger
to complete `SUPPORTED` and `REWORDED` final-content claims; unsupported or
unconfirmed material is omitted and may be described only as neutral missing
information.

Release `1.5.3` fixes the application opening to exactly `Please consider my
application for this role.` and the closing to exactly `Thank you for
considering my application.`. Each bookend has its own isolated `SUPPORTED`
claim containing only its exact final pointer and exactly the canonical
generation-intent, job-title and company evidence IDs. Every other
non-identity final claim must include purpose-compatible confirmed claimant
evidence; canonical job or request evidence may supplement but cannot replace
it. Release `1.5.4` makes that isolation structural in the private provider
wire contract. Schema `3.5.0` requires
`canonicalApplicationClaims.opening` and `.closing` with fixed IDs
`CLAIM-9001` and `CLAIM-9002`, singleton scalar canonical evidence fields and
one fixed bookend path each. Ordinary claims exclude both paths and all 9xxx
IDs and are capped at 38. Parser `3.3.0` validates that raw envelope before it
deterministically projects the two siblings into the unchanged public claims
ledger. It does not split or repair a grouped claim and does not retry the
provider; any mismatch still fails closed under claim policy `2.10.0`. The
local four-disposition claim policy remains compatible with historical data.
Release `1.5.5` makes every required `coreSkills.evidence` value exactly empty.
Release `1.5.6` additionally restricts ordinary claim paths to the exact
claim-bearing leaves (`qualificationName`, never `qualificationTitle`) and
excludes canonical bookends, core skills and hidden evidence. Its private
ordinary-claim array is capped at 26, reserving two public-ledger entries for
the canonical claims and up to 12 for deterministic skill provenance within
the unchanged 40-claim public bound. Parser `3.4.0` then projects skills
deterministically from approved CV skill facts: it keeps
unique exact approved proposals, discards unsupported or duplicate values,
fills in evidence order to `min(8, available)`, caps the result at 12, fixes
`evidence` to empty and creates one exact, server-owned claim using the
selected fact ID for each skill. Versioned
catalogues use only CV `EVIDENCE_SNAPSHOT` `DEMONSTRATED_SKILL` facts; legacy
catalogues use `PROFILE.SKILL` facts. Claim policy `2.11.0` applies only to
that fixed-reservation projection contract.

Historical release `1.5.7` added the required private `personalSummaryClaim` sibling
with fixed ID `CLAIM-9003`, exact `/cv/personalSummary` path, empty review text,
`SUPPORTED` or `REWORDED` disposition and confirmed CV-purpose claimant
evidence. Schema `3.8.0` also removes both document-title paths and the personal
summary from ordinary claims and permits at most 29 ordinary claims. Parser
`3.5.0` projects all three private siblings. Claim policy `2.12.0` attaches each
canonical title path to a same-purpose identity claim citing `JOB.TITLE` (or
creates a bounded one-path identity claim), and clears a non-empty optional
`tailoredDescription` when no accepted ordinary claim owns it. It normalises
the non-skill ledger first, caps accepted unique approved proposals at the
smaller of 12 and the remaining 40-claim capacity, and fills in evidence order
to `min(8, available)`. It fails safely if that minimum cannot fit.

Active release `1.5.8` retains schema `3.8.0` and parser `3.5.0` but replaces
that versioned skill projection under claim policy `2.13.0`. Revision-bound
profile skills are CV-only exact candidates alongside demonstrated skill facts;
the service de-duplicates them by normalised value and prefers demonstrated
support. It keeps only exact model-selected candidates relevant to the job,
caps them at 12 and does not fill a minimum or arbitrary quota. A declared-only
profile skill may appear only in CV `coreSkills`; it cannot ground CV narrative
or cover-letter prose. Cover letters may weave skills supported by demonstrated
career evidence into prose, but quality policy `1.2.0` rejects a literal skills
list. Schema `2.0` continues to reject browser-positioned profile employment and
qualification records.

Immutable release `1.5.6` is the immediate emergency rollback and retains
schema `3.7.0`, evaluation policy `1.5.4`, parser `3.4.0` and claim policy
`2.11.0`. Release `1.5.7` is preserved in version-control history but is not
packaged or included in the approved index: it shares schema `3.8.0` with
`1.5.8` but encodes the previous minimum-eight semantics and cannot be safely
distinguished by schema-derived runtime policy.
Release `1.5.5` remains an approved deeper compatibility release at
schema `3.6.0`, parser `3.3.0` and claim policy `2.10.0`.

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
