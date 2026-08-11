# Prompt bundle governance

CV and Cover Letter Service owns provider-neutral domain prompt construction.
Each approved release under `src/main/resources/prompts/bundles` packages the
template, generation rules, output schema and synthetic evaluation policy with
immutable component checksums. `index.json` is the only runtime allowlist.

The active `cv-cover-letter-1.6.0` release converts the normalised profile and
canonical job into a stable approved-evidence catalogue and treats that
catalogue and normalisation warnings as untrusted data. It places the safety
rules before that evidence and forbids direct, indirect, encoded, nested,
Unicode-obfuscated and schema-escape instructions in source content from
changing the task. The release also owns exact bounded JSON Schema `4.0.0` and
evaluation policy `1.6.0`, used at the provider boundary and by the local
response parser and synthetic evaluation checks. The domain bundle cannot name
a model provider or transport API; provider mechanics belong behind LLM
Gateway. Applied parser `3.6.2`, claim policy `2.24.0` and deterministic quality
policy `1.5.0` complete the active local validation route.

Release `1.5.0` adds a dedicated project structure, separates employment from
project evidence, caps and deduplicates confirmed skills, requires the correct
generic UK sign-off, and instructs the provider to represent every selected
evidence entry in final content for its purpose. The deterministic quality
policy is enabled for the project-aware schema shape containing `cv.projects`;
selecting an approved `1.5.x` rollback retains the same strict local grounding
validation.
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

Release `1.5.6` narrows ordinary `contentPaths` to the exact claim-bearing
leaves. The pattern permits `qualificationName`, never `qualificationTitle`,
and excludes canonical bookends, containers, all core-skill paths and hidden
evidence. Its ordinary array is capped at 26, reserving two public
entries for canonical claims and up to 12 for projected skill provenance
within the unchanged 40-claim bound. Parser `3.4.0` and claim policy `2.11.0`
then project core skills from
approved purpose-compatible facts, preserving unique exact approved proposals,
discarding unsupported or duplicate values, filling in evidence order to
`min(8, available)`, capping at 12 and forcing evidence to empty. Versioned
catalogues use only CV `EVIDENCE_SNAPSHOT` `DEMONSTRATED_SKILL` records; legacy
catalogues use only `PROFILE.SKILL`. The schema rejects model-authored skill
paths before claim validation, and the service creates one exact claim per
projected skill using the selected fact ID without a second provider call. Request-specific
opaque IDs and free-text evidence remain in the untrusted catalogue; the
immutable trusted schema is not rewritten per request.

Historical release `1.5.7` added a third private claim sibling. Schema `3.8.0` requires
`personalSummaryClaim` with fixed ID `CLAIM-9003`, fixed
`/cv/personalSummary` path, empty review text, a `SUPPORTED` or `REWORDED`
disposition and confirmed CV-purpose claimant evidence. Ordinary claims may no
longer own the personal summary or either document-title path and are capped at
29. Parser `3.5.2` projects all three private siblings and claim policy `2.12.0`
normalises the non-skill ledger before allocating skills. It attaches each
canonical title to the same-purpose identity claim citing `JOB.TITLE`, creating
a bounded one-path identity claim only when no compatible claim exists, and it
clears a non-empty optional `tailoredDescription` that has no accepted ordinary
claim. It caps accepted unique approved skill proposals at the smaller of 12
and the capacity remaining within the 40-claim public bound, then fills in
evidence order to `min(8, available)`. Inability to preserve that minimum fails
closed; it does not trigger another provider call.

Historical release `1.5.9` retains the `3.8.0` private wire shape and parser
`3.5.2`, while rules `1.5.9`, evaluation policy `1.5.7` and claim policy
`2.15.0` replace that versioned minimum-fill behaviour and bound deterministic
evidence enrichment to the per-claim schema maximum. The approved CV skill
catalogue is the union of purpose-compatible `DEMONSTRATED_SKILL` snapshot
facts and revision-bound profile `DECLARED_SKILL` facts. Exact normalised
duplicates prefer demonstrated support. The model may select up to 12 exact,
job-relevant candidates; the service discards unsupported, duplicate and
job-advert-only proposals and does not fill a minimum or quota. Revision-declared
facts are service-projected only into CV `coreSkills` and cannot be cited for CV
narrative or cover-letter prose. Cover-letter skills must instead be supported
by demonstrated career evidence, and quality policy `1.5.0` rejects a literal
skills-list heading. The legacy positional profile catalogue retains its
historical minimum-fill compatibility behaviour.

Release `1.5.9` keeps revision-declared records in the server-owned validation
catalogue but exposes only their exact values to the model through
`serviceProjectedCoreSkillCandidates`. Their stable evidence IDs are withheld
from the model-facing catalogue, so a declared core-skill proposal cannot
accidentally cite its service-only ID in narrative provenance. Snapshot evidence
IDs remain available for ordinary claim citations.

Active release `1.6.0` retains the inline narrative schema in schema `4.0.0`
and applies parser `3.6.2`. Project
highlights, work responsibilities and cover-letter body paragraphs are closed
objects containing text, disposition and approved evidence IDs. The parser
projects each item into its own claim in the unchanged public document and
ledger shapes, while the
ordinary provider ledger excludes their paths. This makes the evidence
relationship structural without adding a second provider call.

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
Release `cv-cover-letter-1.5.6` is the immediate approved emergency rollback
for `1.5.8`; its immutable files must not be edited to adopt the dedicated
personal-summary claim, title attachment, optional-description clearing or
adaptive claim budget. It retains schema `3.7.0`, evaluation policy `1.5.4`,
parser `3.4.0`, claim policy `2.11.0` and its fixed reservation for up to 12
projected skills. Release `1.5.7` is preserved in version-control history but
is not packaged or included in `index.json`: it shares schema `3.8.0` with the
active release while its instructions require the previous minimum-eight
projection, so schema-derived parser and claim-policy routing cannot safely
distinguish it. Release `1.5.5` remains an approved deeper compatibility
release with schema `3.6.0`, parser `3.3.0` and claim policy `2.10.0`. Release
`1.5.3` retains the ordinary claim-array contract and parser metadata `3.2.0`.

The oldest approved releases contain output exemplars rather than the active
bounded schema. They are compiled into closed structural schemas at runtime and
remain subject to the response, text, array and active-content limits associated
with applied parser metadata `3.2.0`. Parser `3.3.0` applies to the dedicated
canonical-claim shape; parser `3.4.0` additionally requires the exact ordinary
path pattern before enabling fixed-reservation skill projection. Parser `3.5.2`
requires the schema `3.8.0` personal-summary sibling and active ordinary path
pattern before enabling title attachment, optional-description clearing and
adaptive skill projection. Each parser projects only the private siblings
governed by its selected schema and only after raw schema validation. Releases
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
