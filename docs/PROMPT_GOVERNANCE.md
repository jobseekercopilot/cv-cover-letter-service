# Prompt bundle governance

CV and Cover Letter Service owns provider-neutral domain prompt construction.
Each approved release under `src/main/resources/prompts/bundles` packages the
template, generation rules, output schema and synthetic evaluation policy with
immutable component checksums. `index.json` is the only runtime allowlist.

The active `cv-cover-letter-1.5.5` release converts the normalised profile and
canonical job into a stable approved-evidence catalogue and treats that
catalogue and normalisation warnings as untrusted data. It places the safety
rules before that evidence and forbids direct, indirect, encoded, nested,
Unicode-obfuscated and schema-escape instructions in source content from
changing the task. The release also owns exact bounded JSON Schema `3.6.0` and
evaluation policy `1.5.3`, used at the provider boundary and by the local
response parser and synthetic evaluation checks. The domain bundle cannot name
a model provider or transport API; provider mechanics belong behind LLM
Gateway.

Release `1.5.0` adds a dedicated project structure, separates employment from
project evidence, caps and deduplicates confirmed skills, requires the correct
generic UK sign-off, and requires every selected evidence entry to appear in
final content for its purpose. The deterministic quality policy is enabled
for the project-aware schema shape containing `cv.projects`; selecting an
approved `1.5.x` rollback retains the same strict local grounding validation.
Release `1.5.1` adds an exhaustive final-pointer checklist, dynamic-index
instructions and a complete coverage exemplar so generic narrative is not
silently omitted from the model-produced claim ledger. Release `1.5.2` makes
that provider ledger final-content-only: each claim is `SUPPORTED` or
`REWORDED`, has evidence and final paths, and has empty review text. Missing or
unsupported material is omitted from the documents and claims and may appear
only as a neutral `generationNotes.missingInformation` item. The broader local
and published four-disposition ledger remains compatible with historical and
rollback output. Release `1.5.3` fixes the opening paragraph to exactly `Please
consider my application for this role.` and the closing paragraph to exactly
`Thank you for considering my application.`. Each bookend must be an isolated
`SUPPORTED` claim containing only its exact pointer and exactly the canonical
generation-intent, job-title and company evidence IDs. All other non-identity
versioned claims require purpose-compatible confirmed claimant evidence; job
or request evidence may supplement but cannot replace it.

Release `1.5.4` makes bookend isolation structural in the private provider
wire shape. Schema `3.5.0` requires
`canonicalApplicationClaims.opening` and `.closing` with fixed IDs
`CLAIM-9001` and `CLAIM-9002`, `SUPPORTED`, exact singleton scalar canonical
evidence fields, one fixed scalar path and empty review text. Ordinary claims
cannot use either bookend path or any 9xxx ID and are capped at 38. Parser
`3.3.0` validates the complete raw envelope and then deterministically projects
the two siblings into the unchanged public claims ledger. It never splits or
repairs a grouped claim and does not retry generation; invalid output remains
fail-closed under unchanged claim policy `2.10.0`.

Release `1.5.5` closes the remaining private skill-field mismatch. Rules
`1.5.5` and schema `3.6.0` require every `coreSkills.evidence` value to equal
the empty string, so only the skill name is final claim-bearing content and its
claim evidence IDs retain provenance. The parser does not trim, discard or
repair a non-empty value; strict schema validation rejects it before claim
validation. Parser `3.3.0` remains the applied parser because its validated
canonical-sibling projection algorithm is unchanged.

Runtime assembly preserves those domains as separate LLM Gateway v2 fields:
reviewed bundle text becomes `trustedInstructions`, normalised evidence becomes
`untrustedInput`, and the bundle's reviewed schema becomes the independent
strict JSON Schema output contract. Source data is never interpolated into the
trusted field. See
[`PROMPT_INJECTION_THREAT_MODEL.md`](PROMPT_INJECTION_THREAT_MODEL.md) for the
trust zones, attack corpus, safe-failure and incident procedures.

## Release metadata

Every successful generation response and its structured prompt-build log
records:

- release, bundle, template, rules, schema and evaluation-policy versions;
- SHA-256 digests for the complete bundle and each governed component,
  including the evaluation policy;
- the stable schema identifier.

This metadata contains neither the final prompt nor profile, job, warning,
generated-document or credential data. Durable persistence of the same
metadata with the immutable document version depends on DOC-06. Until that
contract is available, application logs and the immediate API response are
operational evidence but not the final audit record.

## Reviewed change procedure

1. Copy the current approved release to a new semantic-version directory.
2. Change only the components required by the proposal.
3. Recalculate the component checksums in `manifest.json`.
4. Set the new release to `ACTIVE`, retain one previous reviewed release as
   `ROLLBACK`, and update `index.json` atomically.
5. Review the component diff and `PromptBundleComparison` result.
6. Add or update synthetic factuality, quality and prompt-injection fixtures.
7. Deliberately review and replace the golden synthetic LLM-boundary hash.
8. Run the prompt policy, contract policy, Maven and source-only container
   checks before merge.
9. Record material evaluation evidence in the issue and pull request. Never
   use live job-seeker data, paid requests or production credentials for this
   repository test suite.

Changes to provider parameters, model selection or provider transport are not
domain prompt releases and must be governed in LLM Gateway.

## Rollback

Set `PROMPT_BUNDLE_RELEASE` to an approved release ID already packaged in the
same immutable build, then restart. Startup fails closed if the selection is
unapproved, malformed, missing or checksum-invalid. Emergency rollback must
not edit a released directory or bypass the index; a permanent default change
uses the full reviewed change procedure.

Rollback restores prompt construction only. The LLM Gateway v2 physical
trusted/untrusted separation remains enforced for every approved release. It
does not reverse documents already generated or stored, so operators must use
the recorded release and component hashes when identifying affected outputs.
Release `cv-cover-letter-1.5.4` is the immediate approved emergency rollback
for `1.5.5`; its immutable files must not be edited to adopt the exact-empty
skill constraint. It retains schema `3.5.0`, the dedicated canonical-claim
wire shape and applied parser metadata `3.3.0`. Release `1.5.3` remains a
deeper rollback with the ordinary claim-array contract and parser metadata
`3.2.0`.

The oldest approved releases contain output exemplars rather than the active
bounded schema. They are compiled into closed structural schemas at runtime and
remain subject to the response, text, array and active-content limits associated
with applied parser metadata `3.2.0`. Parser `3.3.0` applies only when the
selected schema contains the exact dedicated canonical-claim structure; it
projects that private structure only after raw schema validation. Releases
from `1.2.0` onward have specific field-by-field bounds at both the provider
and local validation boundaries; releases from `1.3.0` onward enable the claim
evidence ledger. The `1.5.x` post-grounding quality policy is gated by the
project-aware schema so approved rollback remains usable with an evidence
catalogue.

## Verification

```bash
./scripts/test-prompt-bundle-policy.sh
./scripts/verify-prompt-bundles.sh
mvn -B --no-transfer-progress clean verify
docker build --tag local/cv-cover-letter-service .
```

The shell policy rejects checksum drift, missing or extra release files,
unapproved packaged directories, provider-specific instructions, invalid
default/rollback state and a malformed active strict schema. Java startup
repeats the allowlist, manifest, placeholder, checksum, schema and
provider-boundary checks. Synthetic tests pin factuality and quality rules,
the injection corpus and a reviewed golden LLM boundary.
