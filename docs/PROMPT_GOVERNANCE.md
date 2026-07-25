# Prompt bundle governance

CV and Cover Letter Service owns provider-neutral domain prompt construction.
Each approved release under `src/main/resources/prompts/bundles` packages the
template, generation rules, output schema and synthetic evaluation policy with
immutable component checksums. `index.json` is the only runtime allowlist.

The active `cv-cover-letter-1.2.0` release treats profile, canonical job and
normalisation warnings as untrusted evidence. It places the safety rules before
that evidence and forbids direct, indirect, encoded, nested, Unicode-obfuscated
and schema-escape instructions in source content from changing the task. The
release also owns the exact bounded JSON Schema `2.0.0` used at the provider
boundary and by the local response parser. The domain bundle cannot name a
model provider or transport API; provider mechanics belong behind LLM Gateway.

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
The older approved releases contain output exemplars rather than the active
bounded schema. They are compiled into closed structural schemas at runtime and
remain subject to parser `2.0.0` response, text, array and active-content
limits. Only release `1.2.0` has the more specific field-by-field bounds at
both the provider and local validation boundaries.

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
