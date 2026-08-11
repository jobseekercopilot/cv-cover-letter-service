# Rejected-generation quarantine and replay runbook

## Purpose and non-goals

The quarantine preserves a complete typed LLM Gateway response only when the
durable draft endpoint has already received it and local deterministic
validation rejects it. It allows an operator to diagnose or recover that paid
response after a validator correction without another provider disclosure or
request.

Replay is not generation, approval, persistence, export, or billing. It never
calls LLM Gateway, OpenAI, Payment, Document Store, or Application Tracker. A
successful replay returns a bounded draft to the authenticated operator and
does not publish it. Any later persistence must use the normal owner,
provenance, family/version, approval, idempotency, and billing-coordinator
boundaries.

The quarantine cannot recover responses rejected before this feature was
enabled. In particular, it cannot reconstruct an output from logs, token
usage, or provider request IDs.

## Runtime configuration

Enable the feature only with an encrypted persistent volume and runtime secret
injection:

| Environment variable | Required value |
| --- | --- |
| `REJECTED_GENERATION_QUARANTINE_ENABLED` | `true` |
| `REJECTED_GENERATION_QUARANTINE_DIRECTORY` | Absolute path on the dedicated persistent volume |
| `REJECTED_GENERATION_QUARANTINE_KEY_BASE64` | Base64 encoding of exactly 32 random bytes |
| `REJECTED_GENERATION_OPERATOR_TOKEN` | Dedicated random token of at least 32 UTF-8 bytes, distinct from every service token |
| `REJECTED_GENERATION_QUARANTINE_RETENTION` | Between `PT5M` and `P7D`; default `PT24H` |
| `REJECTED_GENERATION_QUARANTINE_CLEANUP_INTERVAL` | Between `PT1M` and `PT24H`; default `PT15M` |
| `REJECTED_GENERATION_QUARANTINE_MAX_ARTIFACT_BYTES` | 16 KiB to 1 MiB; default 256 KiB plaintext |
| `REJECTED_GENERATION_QUARANTINE_MAX_ARTIFACTS` | 1 to 10,000; default 1,000 |
| `REJECTED_GENERATION_QUARANTINE_MAX_REPLAY_EVENTS` | 1 to 1,000; default 100 |
| `REJECTED_GENERATION_ALLOW_OPERATION_BOUND_CONTEXT_DRIFT` | `false` by default; incident-only compatibility switch |

Missing, weak, reused, malformed, relative-path, symlink, or unwritable enabled
configuration prevents startup. The container runs as the unprivileged
`cvservice` user. The directory is owner-only and each artifact is owner
read/write only on POSIX filesystems. The underlying volume must also enforce
host/cloud encryption, backup exclusion, least-privilege attachment, and
capacity monitoring.

The Actuator health contribution is `DOWN` when enabled storage is missing,
unwritable, full, or at its artifact-count limit. It reports only enablement,
count, and usable-byte metadata; paths, owners, diagnostics, and content are
never exposed. Disabled quarantine contributes `UP` with `enabled: false`.

Do not place either secret in Compose files, images, shell history, tickets, or
logs. Use the approved secret manager or protected local secrets file. Never
back up or replicate the quarantine as ordinary document storage.

## What is retained

Each operation has at most one encrypted AES-256-GCM artifact. The operation
UUID is authenticated as associated data. The encrypted plaintext contains:

- the owner binding and capture/expiry timestamps;
- the exact bounded typed `GenerationResponse` received from LLM Gateway;
- SHA-256 digests of that response and the complete canonical generation
  request;
- immutable prompt release, bundle, schema and evaluation-policy provenance;
- parser and claim-policy versions, model identifier and token usage;
- bounded structured rejection diagnostics; and
- a sequence-numbered SHA-256 hash chain of capture and replay outcomes.

It does not copy the trusted instructions, source prompt/evidence request,
strict schema body, contact details, service/provider credentials, authorization
headers, or provider error responses. The typed generated output can contain
personal data and must still be treated as sensitive.

## Inspect and replay

Use an approved operator session. Supply the owner from the authenticated
Document Generation operation, never from a browser or guessed identifier.
The examples assume the operator token is injected into the process environment
by approved tooling and the original bounded request is in a protected local
file.

Inspect metadata without returning generated content:

```bash
curl --fail-with-body \
  -H "X-Operator-Token: ${REJECTED_GENERATION_OPERATOR_TOKEN}" \
  -H "X-Document-Owner: ${DOCUMENT_OWNER}" \
  "${CV_SERVICE_URL}/internal/v1/cv-cover-letter/rejected-generations/${OPERATION_ID}"
```

Replay the exact response with the original bounded request:

```bash
curl --fail-with-body \
  -X POST \
  -H "Content-Type: application/json" \
  -H "X-Operator-Token: ${REJECTED_GENERATION_OPERATOR_TOKEN}" \
  -H "X-Document-Owner: ${DOCUMENT_OWNER}" \
  --data-binary @protected-generation-request.json \
  "${CV_SERVICE_URL}/internal/v1/cv-cover-letter/rejected-generations/${OPERATION_ID}/replay"
```

For a response captured from an explicitly selected draft, supply the original
`SelectedDraftGenerationRequest` and the matching `CV` or `COVER_LETTER`
selector:

```bash
curl --fail-with-body \
  -X POST \
  -H "Content-Type: application/json" \
  -H "X-Operator-Token: ${REJECTED_GENERATION_OPERATOR_TOKEN}" \
  -H "X-Document-Owner: ${DOCUMENT_OWNER}" \
  --data-binary @protected-selected-generation-request.json \
  "${CV_SERVICE_URL}/internal/v1/cv-cover-letter/rejected-generations/${OPERATION_ID}/replay/${OUTPUT_TYPE}"
```

The service rebuilds the recorded approved prompt release. A request, evidence,
schema, bundle, or owner mismatch returns `409` or `404` without exposing the
artifact. A replay result is either `ACCEPTED` with the recovered bounded draft
or `REJECTED` with the current structured diagnostic. Both explicitly report
`providerInvocationCount: 0` and append a tamper-evident audit event. Repeated
replay is bounded and does not change billing or ordinary document state.

If a code change causes the rebuilt derived LLM request digest to drift while
the operation, owner, prompt release, bundle, schema and evaluation policy all
remain exact, the compatibility switch may be enabled for an approved
incident recovery. The current owner-bound source request is still used for
all parsing, evidence grounding and quality validation; provider invocation
remains impossible. Any immutable prompt provenance mismatch still returns
`409`. Each accepted drift is logged with the operation and prompt release,
and the switch should be disabled again after the retained operation is
published.

## Explicit deletion and retention

Delete an artifact after successful recovery, a privacy request, or incident
closure:

```bash
curl --fail-with-body \
  -X DELETE \
  -H "X-Operator-Token: ${REJECTED_GENERATION_OPERATOR_TOKEN}" \
  -H "X-Document-Owner: ${DOCUMENT_OWNER}" \
  "${CV_SERVICE_URL}/internal/v1/cv-cover-letter/rejected-generations/${OPERATION_ID}"
```

Expired artifacts return `404` and are deleted on access, capture, and the
bounded scheduled cleanup. Deletion is irreversible. Record the operation ID,
reason, timestamp, and approved incident reference without copying content.

## Storage failure, integrity failure, and rotation

If capture fails, the original safe `422` rejection remains authoritative and
the service logs only the operation ID and failure type. Do not make another
provider call merely to recreate the response. Stop live generation, repair
the volume/capacity/key configuration, and record the uncaptured operation in
the incident.

Authentication, digest, identity, audit-chain, format, size, or decryption
failure makes the artifact unavailable; never bypass those checks. Preserve
the encrypted file and non-payload metadata for security review.

To rotate the encryption key, disable new generation, replay or explicitly
delete every required artifact under the old key, stop the service, replace the
key through the secret manager, and restart. Old artifacts are deliberately
unreadable with the new key. Rotate the operator token independently and revoke
the old value before resuming operator access.

## Verification

Use synthetic fixtures only. Run the complete repository verification contract
and container health check. Negative evidence must cover weak/reused secrets,
wrong owner, missing/duplicate headers, wrong key, tampered ciphertext, expired
and deleted artifacts, size/count/event limits, request-digest mismatch,
duplicate capture, storage failure, accepted/rejected replay, and zero provider,
billing, storage, or application calls.
