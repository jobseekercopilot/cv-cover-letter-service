# Beta-readiness audit

Audit date: 2026-07-25

Status: **Not ready for private beta**

## Verified responsibility

`CvCoverLetterController` accepts a combined profile and job request.
`PromptBuilderService` serialises both objects into one prompt using the files
under `src/main/resources/prompts`. `CvCoverLetterService` reserves payment
credits, calls `llm-gateway`, parses the response, renders two plain-text
documents, saves the CV and cover letter, creates an application, and commits
the credit reservation.

The service therefore owns domain prompt construction and first-pass response
validation. It does not own provider transport, exported binary rendering, or
the underlying document database.

## Migration evidence

- Source was copied from the untracked service directory in the intact root
  workspace; no standalone source history was available.
- `target/`, local client JARs, generated binaries, logs, databases, exported
  documents, recordings, and environment files are excluded.
- The migration-time contract is `contracts/openapi.json`.
- Gitleaks and targeted personal-data checks passed on the source snapshot.
- No live model request was made.
- The DOCGEN-02 CV/Cover Letter slice replaces three imported `systemPath`
  clients with deterministic source generation from exact
  revision/checksum-pinned producer contracts. It removes an unused Payment
  client JAR and checks the handwritten Payment adapter against the pinned
  Payment producer contract. Contract policy tests, Maven verification and the
  source-only container build run in CI without sibling repositories, local
  `libs/` or preinstalled Job Seeker Copilot artifacts.
- OWASP Dependency-Check 12.1.8 completed against the cached 2026-07-18
  advisory database: 46 dependencies, 10 vulnerable dependencies, 142
  vulnerability matches, including 18 Critical and 39 High matches. Results
  require reachability/false-positive triage; the report was not committed.
- The CVCL-02 producer slice authenticates the Document Generation Gateway
  with a dedicated runtime credential, accepts exactly one trusted owner
  context, excludes caller-controlled body identity from domain work, and
  published the breaking identity contract as OpenAPI `2.0.0`.
- The DOCGEN-04 producer slice replaces broad duplicated profile/job DTOs with
  the versioned bounded input contract in
  `docs/GENERATION_INPUT_CONTRACT.md`. It validates source provenance, field
  and aggregate limits, dates, unknown fields, active markup, duplicates and
  conflicts before prompt construction. Contact identity is render-only, and
  deterministic warnings represent missing or changed evidence.
- The DOCGEN-05 foundation packages template, rules, output schema and
  evaluation policy into checksum-protected semantic releases. Startup and CI
  reject unapproved drift, the previous release remains selectable for
  rollback, synthetic policy tests cover representative injection forms, and
  non-PII release metadata is returned and logged with each generation.
- The DOCGEN-06 consumer slice uses LLM Gateway v2 instead of the deprecated
  raw-prompt operation. Reviewed instructions, normalised untrusted evidence
  and the compiled strict JSON Schema remain separate across the producer
  boundary. Job-description and work-history tests cover direct, indirect,
  encoded, nested, Unicode and schema-escape attacks; unsafe LLM completion
  states fail before persistence; input/output sentinels prove payloads are not
  logged.
- The DOCGEN-07 foundation releases strict bounded output schema `2.0.0` in
  prompt bundle `1.2.0`. The same schema is sent to LLM Gateway and enforced
  locally. The parser rejects malformed, truncated, fenced, trailing,
  duplicate, incomplete, unknown, wrong-type, oversized, active-markup and
  control-character output before any document or application write and
  releases the billing reservation. Prompt/schema, LLM-contract and parser
  versions are logged without output payloads. The final consumer slice pins
  the DOCGEN-10 gateway audit contract, validates its actual model and
  deployment/policy evidence before persistence, records the exact model in
  the Payment commit and logs the complete non-payload version set.
- The DOCGEN-08 foundation releases prompt bundle `1.3.0` and schema `3.0.0`.
  It sends only a server-built approved-evidence catalogue to the model,
  requires explicit supported, reworded, confirmation-required or rejected
  dispositions, and validates complete final-path coverage before rendering.
  Unknown evidence, duplicated or missing paths, review-only material in final
  content, mismatched atomic facts, uncited metrics and unsupported
  tool/qualification/motivation/availability/salary/right-to-work claims fail
  before document, application or billing-commit side effects.

## Confirmed blockers

1. Gateway-to-service identity is now authenticated at this producer boundary.
   Beta still requires the Gateway consumer rollout, runtime secret wiring,
   outbound Payment/Document Store/Application Tracker identities, and
   fleet-level negative-path evidence.
2. The bounded input allowlist and contact-data minimisation are enforced at
   this producer. The Gateway consumer must adopt OpenAPI `3.1.0`, and
   provider-retention/regional-processing decisions remain open.
3. Job and profile content is physically separated as untrusted evidence
   behind explicit rules and a synthetic attack corpus. A current official
   provider-retention/regional-processing decision and controlled provider
   evaluation remain program-level release evidence; no live or paid request
   was made in DOCGEN-06.
4. Prompt releases, comparison, change evaluation and operational rollback are
   governed. The release metadata is returned and logged, but durable
   attachment to immutable document versions remains blocked on DOC-06.
5. CV and cover-letter generation are coupled into one prompt and request.
6. Strict schema enforcement, bounds, active-content rejection and
   prompt/schema/parser/model/deployment contract metadata are implemented.
   Durable attachment of the complete generation provenance to immutable
   document versions remains dependent on DOC-06.
7. Generated claims now carry approved evidence references and explicit
   dispositions, and unsupported material fails closed. Durable attachment of
   this provenance to immutable stored documents remains dependent on DOC-06.
8. Unsupported professional inference is forbidden. The controlled, audited
   user correction and approval path remains dependent on DOCGEN-16.
9. Document Store and Application Tracker writes now use distinct,
   runtime-injected producer identities. Store creates carry the authenticated
   Gateway owner as explicit owner context, and Tracker creates use that same
   trusted owner. Infrastructure injection/rotation and integrated negative
   paths remain open.
10. Documents and an application are persisted before any user review or
   approval.
11. The save/export/application/credit sequence is non-atomic. Retries can
    leave partial records, duplicate documents/applications, or repeated cost.
12. Normalised prompt input is capped at 40,000 characters. There is no
    request idempotency key, duplicate-click protection, or service-owned
    per-user usage guard.
13. The gateway now supplies versioned operational provider-cost evidence from
    actual usage. The pre-reservation token estimate remains approximate, and
    customer pricing, AI Credit valuation and exhaustion policy remain Payment
    workstream dependencies.
14. Current tests cover the prompt-injection and hallucination corpora plus
    malformed, truncated,
    fenced, trailing, duplicate, missing, null, unknown, wrong-type, oversized,
    active-content, filtered and schema-mismatched output handling. They do not
    yet cover duplicate requests, every partial downstream failure, user
    approval or the complete privacy evidence required for beta.
15. Document Generation Gateway must consume the bounded authenticated `3.1.0`
    contract and provide its own dedicated credential under DOCGEN-03/GW-01.
16. Current Spring, Tomcat, Jackson, logging, Swagger UI, and generated-client
    dependency findings include untriaged Critical/High advisories.

## Required validation

Before beta, evidence must show authenticated identity propagation, minimal
approved data sent to the model, versioned injection-resistant prompts,
schema-enforced structured output, claim provenance, explicit user approval,
idempotent recovery, bounded cost, reproducible contracts/clients, and passing
unit, component, contract, security, prompt, failure, and browser tests.

The provider retention, training, and regional-processing decision remains
open and must be based on current official provider documentation and the
applicable product agreement. This audit is not legal or GDPR certification.
