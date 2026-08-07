# Prompt-injection threat model

## Scope and security objective

CV and Cover Letter Service treats every value derived from a job advert,
profile, employment history, qualification or normalisation warning as
untrusted data. The service must use that evidence to tailor documents without
allowing it to change the generation task, reviewed rules, output contract or
access boundary.

Existing CV or cover-letter content is not accepted by the current `3.1.0`
input contract. An `existingDocument` field therefore fails closed as unknown
before generation. If a future contract accepts existing documents, their
content must enter the same untrusted channel and the adversarial corpus must
cover it before release.

## Assets and trust boundaries

| Asset or input | Trust | Enforcement |
| --- | --- | --- |
| Versioned prompt template and generation rules | Trusted after allowlist and checksum verification | Immutable prompt-bundle registry |
| Task, release and output-schema metadata | Trusted service configuration | Service-owned values; caller cannot supply them |
| Profile, job and warning values | Untrusted | Bounded DTOs, normalisation and a dedicated untrusted JSON envelope |
| Model output | Untrusted until validated | LLM Gateway strict JSON Schema plus exact local schema and active-content checks |
| Contact identity | Sensitive, render-only | Removed before model input and added only by local renderers |
| Provider transport and credentials | Outside this service | LLM Gateway adapter boundary |
| Rejected typed model response | Sensitive and untrusted | AES-256-GCM quarantine, owner/operator authorization, bounded retention and deterministic replay only |

The service calls LLM Gateway `POST /api/v2/generations`. Reviewed instructions
are sent as `trustedInstructions`; the JSON evidence envelope is sent as
`untrustedInput`. LLM Gateway maps those fields to separate provider roles.
The service never concatenates source content into the trusted field.

The active immutable bundle contains a reviewed, bounded JSON Schema: every
named property is required, nested objects reject additional properties,
strings have explicit patterns, and arrays have item-count limits and explicit
item shapes. The same schema object is sent through the v2 output contract and
enforced locally on the response rather than included in either text channel.

## Attack classes and expected handling

The synthetic evaluation policy covers direct rule replacement, instructions
quoted as recruiter notes, encoded instructions, nested JSON instructions,
Unicode-obfuscated text and attempts to escape the output schema. Each case is
tested in both job-description and employment-history data.

For every case:

1. The supplied attack remains present only in `untrustedInput`.
2. Reviewed safety and factuality rules remain only in `trustedInstructions`.
3. The strict output schema is independently supplied and unchanged.
4. Prompt-object string representations and structured logs omit both input
   and output payloads.
5. A filtered, incomplete, version-mismatched, schema-mismatched, blank,
   malformed, fenced, duplicated, oversized or active-content response fails
   before document or application persistence and releases the billing
   reservation.

No test in this repository makes a live or paid provider request or uses real
job-seeker data.

## Logging and data exposure

Generation logs may contain owner/job identifiers, durations, token counts,
contract and prompt-release versions, component hashes, field character counts
and downstream record identifiers. They must not contain:

- trusted instruction text;
- the untrusted evidence envelope;
- the strict JSON Schema body;
- raw model output;
- contact details, credentials or provider request bodies.

Tests use unique input and output sentinels to prove that a successful
generation does not place either payload in captured logs. The prompt value
object also excludes all three payload fields from its generated `toString`.

When quarantine is enabled, rejected typed output is the only payload retained.
It is encrypted at rest with authenticated operation-bound associated data.
The source prompt, selected evidence envelope, strict schema body, contact
details, credentials, and provider error bodies are not copied into the
artifact. Filenames contain only the non-PII durable operation UUID. Access is
owner-bound through a dedicated operator credential; no enumeration endpoint
exists. Replays are hash-chained, bounded, and never invoke the provider.

## Evaluation and incident procedure

Run the contract policy, prompt policy and complete Maven suite before changing
the selected bundle, LLM contract pin or trust-boundary assembly. A change to
the reviewed golden boundary hash requires deliberate review of the trusted
instructions, untrusted envelope and compiled output schema together.

If injection or data disclosure is suspected:

1. Disable generation or select an already approved rollback release; do not
   edit an immutable bundle in place.
2. Preserve correlation IDs, release/component hashes, contract version and
   schema version without copying prompt or response bodies into tickets.
3. Identify affected generation/document records and prevent publication while
   the incident is assessed.
4. Add a minimal synthetic reproduction to the adversarial corpus.
5. Release changed rules as a new immutable bundle and repeat all policy,
   contract, unit, integration and container checks.

Physical trusted/untrusted separation remains active when selecting an approved
rollback bundle. Older bundles have fewer explicit safety instructions and
their exemplar output is compiled into a closed structural schema, but the
response, text, array and active-content limits pinned by the selected bundle
still apply.

Active prompt release `1.5.11` pins schema `3.10.0`, evaluation policy `1.5.8`,
parser `3.6.0`, claim policy `2.15.0` and quality policy `1.4.0`. Schema `3.10.0`
isolates the two canonical application claims and required `CLAIM-9003`
personal-summary claim, requires exact-empty private skill evidence and
constrains ordinary claims to exact final leaves. Ordinary claims cannot claim
document titles, the personal summary, canonical bookends, core skills or
hidden evidence, and fabricated paths such as `qualificationTitle` reject. The
personal-summary sibling has a fixed path and shape but its approved opaque
evidence IDs remain untrusted request data; free-text evidence is never placed
in the trusted schema.

Parser `3.6.0` validates the complete raw object and active content before any
normalisation. Claim policy `2.15.0` can then attach canonical title paths using
server-owned `JOB.TITLE` provenance and clear only an optional non-empty
`tailoredDescription` that no accepted claim owns. Canonical
`PROFILE_REVISION` `DECLARED_SKILL` records are revision-bound, CV-only
candidates, and the service may project approved candidates only into
`coreSkills`. They are forbidden as model-authored narrative evidence, and
advert-only skills are rejected. The cover letter cannot contain a literal
skill-list heading. These deterministic operations cannot turn source text
into trusted instructions. In particular, unsafe markup rejects during raw
validation, while an invalid claimed description remains present for the
ordinary claim checks; neither is sanitised by the clearing rule. Skill
projection uses only capacity remaining after non-skill normalisation, caps at
12 and never retries invalid provider output.

The immediately approved rollback is immutable release `1.5.6`, which pins
schema `3.7.0`, evaluation policy `1.5.4`, parser `3.4.0` and claim policy
`2.11.0`. Release `1.5.7` is preserved in version-control history but is not
packaged or included in the approved index because schema `3.8.0` cannot
distinguish its prior minimum-eight skill semantics from the semantics of
`1.5.8`. Earlier historical release
`1.5.5` retains schema `3.6.0`, parser `3.3.0` and claim policy `2.10.0`, while
release `1.5.3` retains parser metadata `3.2.0`. Rollback is an emergency
containment action, not evidence that the newer evaluation policy passed.
