# Draft generation boundary

OpenAPI `4.1.0` separates model-domain work from workflow ownership and adds
backward-compatible selected-output operations.

## Operations

`POST /api/v1/cv-cover-letter/drafts/estimate` validates the exact bounded
profile/job snapshot and builds the reviewed prompt in memory. It returns the
conservative token reservation and does not call the model, Payment, Document
Store, or Application Tracker.

`POST /api/v1/cv-cover-letter/drafts` requires:

- the approved Gateway service credential in `X-Service-Token`;
- the authenticated owner bound once in `X-Document-Owner`;
- the Gateway's durable UUID in `X-Generation-Operation-Id`;
- bounded profile/job snapshot schema `1.0`.

It calls LLM Gateway once and returns rendered draft content, prompt-release
provenance, token usage, and non-payload provider audit evidence. It does not
reserve or commit credit, save or approve a document, export a file, or create
an application.

`POST /api/v1/cv-cover-letter/drafts/{outputType}/estimate` and
`POST /api/v1/cv-cover-letter/drafts/{outputType}` accept exactly `CV` or
`COVER_LETTER`, schema `2.0`, and the single evidence snapshot whose purpose
matches that output. Their strict provider schema omits the unselected document;
the generation response contains one title/content pair and the usage/audit
evidence for that call only. The estimate has no provider or owning side effect,
and selected generation has no Payment, Document Store, Application Tracker,
approval, export, or publication side effect.

The pre-existing paired `/drafts` and `/drafts/estimate` contracts are retained
unchanged for rolling consumers.

## Ownership and recovery

Document Generation Gateway is the durable coordinator and owns idempotency,
reservation, operation state, retry decisions, persistence, approval, export,
and application linkage. CV and Cover Letter Service owns prompt construction,
output validation, evidence enforcement, and deterministic rendering only.

The operation ID is logged as non-PII correlation evidence and echoed in the
response. It is not a licence to retry an ambiguous provider call: until the
Gateway's durable coordinator records a completed draft response, an ambiguous
model outcome must remain visible for explicit recovery rather than silently
invoking the provider twice.

If the bounded provider response is received but deterministic response,
evidence, or quality validation rejects it, the optional quarantine captures
the exact typed response before returning `422`. Capture is keyed by the durable
operation ID, owner-bound, encrypted, size/count limited, and automatically
expired. The prompt and selected evidence are not copied; only their canonical
request digest and immutable prompt/schema provenance are retained.

An operator replay supplies the original owner-bound `GenerateRequest`. The
service rebuilds the recorded immutable prompt release, requires its canonical
request digest to match, and runs the stored response through current
validators and renderers. Replay reports `providerInvocationCount: 0` and has
no Payment, Document Store, Application Tracker, approval, export, or
publication side effect.

## Transitional endpoint

`POST /api/v1/cv-cover-letter/generate` retains the pre-`3.2.0` combined
payment/store/application behaviour only while the Gateway consumer migrates.
It must not be used by the new workflow. Remove the legacy endpoint and its
Payment, Store, and Tracker credentials after the Gateway operation and
rollback path are verified.
