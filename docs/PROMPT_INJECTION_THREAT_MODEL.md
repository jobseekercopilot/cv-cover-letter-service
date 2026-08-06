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
their exemplar output is compiled into a closed structural schema, but local
response, text, array and active-content limits with applied parser metadata
`3.2.0` still apply. Active release `1.5.5` uses schema `3.6.0` to isolate its
two canonical application claims in a dedicated private provider object and
to constrain every private core-skill evidence scalar to the exact empty
string. Parser `3.3.0` validates that raw object before deterministic projection
into the unchanged public claim ledger; it does not trim, split, repair or
retry invalid provider output. Rollback release `1.5.4` retains schema `3.5.0`
and parser metadata `3.3.0`; release `1.5.3` retains parser metadata `3.2.0`.
Rollback is an emergency containment action, not evidence that the newer
evaluation policy passed.
