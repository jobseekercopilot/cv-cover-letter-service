# Beta-readiness audit

Audit date: 2026-07-23

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
  context, overwrites caller-controlled body identity before domain work, and
  publishes the breaking identity contract as OpenAPI `2.0.0`.

## Confirmed blockers

1. Gateway-to-service identity is now authenticated at this producer boundary.
   Beta still requires the Gateway consumer rollout, runtime secret wiring,
   outbound Payment/Document Store/Application Tracker identities, and
   fleet-level negative-path evidence.
2. The entire enriched profile, including contact data, is serialised for the
   model without a documented allowlist or minimisation decision.
3. Job and profile content are concatenated with instructions, so untrusted
   text can attempt prompt injection.
4. Prompt files are source controlled but have no explicit prompt version,
   change-evaluation process, or rollback identifier stored with results.
5. CV and cover-letter generation are coupled into one prompt and request.
6. The parser checks JSON shape only. It does not enforce the supplied JSON
   schema, reject unknown/oversized fields, sanitise active content, or record
   model/prompt/schema versions.
7. Generated claims have no evidence references or disposition such as
   supported, reworded, confirmation required, or rejected.
8. Prompt rules allow reasonable professional inferences, which can become
   unsupported claims.
9. Documents and an application are persisted before any user review or
   approval.
10. The save/export/application/credit sequence is non-atomic. Retries can
    leave partial records, duplicate documents/applications, or repeated cost.
11. There is no request idempotency key, duplicate-click protection, prompt
    size limit, or service-owned per-user usage guard.
12. Token estimation is approximate and pricing/model-version assumptions are
    not governed or surfaced as cost evidence.
13. Current tests do not cover prompt-injection corpora, unsupported claims,
    malformed/oversized structured output, duplicate requests, partial
    downstream failure, user approval, or privacy minimisation.
14. Document Generation Gateway must consume this authenticated `2.0.0`
    contract and provide its own dedicated credential under DOCGEN-03/GW-01.
15. Current Spring, Tomcat, Jackson, logging, Swagger UI, and generated-client
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
