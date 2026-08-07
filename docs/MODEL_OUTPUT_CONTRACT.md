# Model output contract

CV and Cover Letter Service treats model output as untrusted until it passes
the exact reviewed schema and local safe-content policy. The active prompt
release is `cv-cover-letter-1.5.11`; it owns output schema `3.10.0` and
evaluation policy `1.5.8` at
`src/main/resources/prompts/bundles/cv-cover-letter-1.5.11/`. Its reviewed
`output-schema.json` checksum is pinned in that release's immutable manifest.

## One contract at both boundaries

`PromptBuilderService` loads the active schema as a `JsonNode`.
`CvCoverLetterService` sends that same object in the LLM Gateway v2
`GenerationOutputContract` and passes it to `LlmResponseParser` when the
response returns. This prevents the provider request and local parser from
drifting onto different output shapes.

The schema uses the provider-supported strict JSON Schema subset documented by
the
[OpenAI Structured Outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs):
closed objects, required properties, typed arrays, `minItems`, `maxItems` and
bounded string patterns. Startup and CI reject an active schema outside the
service's deliberately smaller subset.

## Bounds

| Output area | Bound |
| --- | --- |
| Complete raw response | 100,000 characters |
| CV or cover-letter titles | 200 characters |
| Role, job, company, qualification and employer names | 80–160 characters by field |
| CV summaries and tailored descriptions | 2,000 characters |
| Cover-letter body | 3–24 body paragraphs; 3,000 characters per paragraph; enough paragraphs to cover every selected entry |
| Skills | 0–12 exact job-relevant items; no active minimum or quota; private `evidence` is exactly empty |
| Projects | 10 entries; 8 highlights per project |
| Qualifications and work-history entries | 20 items each |
| Responsibilities | 12 per work-history entry |
| Generation-note lists | 20 items; 500 characters per item |
| Active ordinary provider claims | 1–20 claims; 30 evidence IDs and final paths per claim |
| Inline narrative evidence | Required beside every project highlight, work responsibility and body paragraph |
| Immediate `1.5.6` rollback ordinary claims | 1–26 claims; 30 evidence IDs and final paths per claim |
| Older dedicated-sibling ordinary claims | 1–38 claims; 30 evidence IDs and final paths per claim |
| Canonical application provider claims | Exactly two fixed sibling objects |
| Active personal-summary provider claim | Exactly one fixed sibling object |
| Active projected public claim ledger | 4–40 claims |
| Immediate rollback projected public claim ledger | 3–40 claims |

The active `1.5.11` provider ledger contains only `SUPPORTED` and `REWORDED`
final-content claims. Each ordinary claim requires at least one approved
evidence ID and one final path, and `reviewText` is exactly empty. Unsupported
or unconfirmed material is omitted from both the documents and claims; a neutral
`generationNotes.missingInformation` item may describe genuinely absent
information. The published ledger DTO and local rollback validator retain all
four dispositions for historical compatibility. Three required private sibling
objects carry the two canonical application claims and the personal-summary
claim; they are projected into that unchanged public ledger DTO only after raw
schema validation.

Schema `3.10.0` additionally represents every project highlight, work
responsibility and cover-letter body paragraph as a closed provider object with
`text`, `disposition` and one to 30 approved `evidenceIds`. Parser `3.6.0`
validates that raw shape, replaces each object with its text for the existing
document DTOs, and projects bounded claims for every exact zero-based path.
Those paths are excluded from ordinary provider claims, preventing prose and
its provenance from being generated independently.

Schema `3.5.0` fixes `/coverLetter/openingParagraph` to exactly `Please consider
my application for this role.` and `/coverLetter/closingParagraph` to exactly
`Thank you for considering my application.`. Its private provider wire shape
requires `canonicalApplicationClaims.opening` and
`canonicalApplicationClaims.closing`. Those objects have fixed IDs
`CLAIM-9001` and `CLAIM-9002`, disposition `SUPPORTED`, one fixed scalar
`contentPath`, empty `reviewText`, and required singleton scalar
`generationIntentEvidenceId`, `jobTitleEvidenceId` and `companyEvidenceId`
fields fixed to `REQUEST.GENERATION_INTENT`, `JOB.TITLE` and `JOB.COMPANY`.
The ordinary `claims` array excludes both bookend paths and every 9xxx claim ID
and is capped at 38. Every other non-identity versioned final claim must cite
purpose-compatible confirmed claimant evidence; job and request facts alone
cannot support candidate or narrative content. Schema `3.6.0` retains all of
those `3.5.0` invariants and additionally constrains every required
`coreSkills.evidence` field to the singleton empty string. A skill's provenance
belongs to the claim covering `/cv/coreSkills/{i}/name`; arbitrary hidden skill
prose is neither accepted nor silently discarded. Schema `3.7.0` retains those
invariants and replaces the broad ordinary claim-path pattern with the exact
claim-bearing CV and cover-letter leaves. It permits `qualificationName`, not
`qualificationTitle`, and excludes canonical bookends, containers,
`coreSkills` and every hidden evidence path. Its ordinary array is capped at
26, reserving two public-ledger entries for the
canonical siblings and up to 12 for projected skill claims within the fixed
40-claim bound.

Schema `3.8.0` retains those strict leaf and exact-empty skill constraints. It
requires `personalSummaryClaim` with fixed ID `CLAIM-9003`, disposition
`SUPPORTED` or `REWORDED`, one to 30 approved evidence IDs, fixed scalar path
`/cv/personalSummary` and empty `reviewText`. The ordinary array excludes that
path and both document-title paths as well as the earlier reserved paths, and
is capped at 29. The personal-summary sibling must cite confirmed CV-purpose
claimant evidence; canonical job or request facts cannot support it alone.

After the raw `3.8.0` provider envelope passes schema and active-content
validation, parser `3.5.2` projects the three private siblings and policy
`2.14.0` normalises the non-skill claim ledger. It attaches `/cv/title` and
`/coverLetter/title` deterministically to the same-purpose identity claim that
already cites `JOB.TITLE`; if no compatible claim exists, it creates a bounded
one-path identity claim. A non-empty optional work-history
`tailoredDescription` is retained only when an accepted ordinary claim owns its
path; otherwise the service clears it before final exact-coverage validation.
When normalisation merges approved evidence references, it preserves provider
references first and appends supplemental references only up to the schema's
30-reference limit. It does not truncate an already-invalid provider array.

The service then rebuilds `cv.coreSkills` from approved purpose-compatible
skill facts. For versioned input, candidates are CV `EVIDENCE_SNAPSHOT`
`DEMONSTRATED_SKILL` records and revision-bound `PROFILE_REVISION`
`DECLARED_SKILL` records. It preserves only unique exact approved model
selections, discards unsupported, duplicate and job-advert-only values, prefers
demonstrated support for a normalised duplicate, and caps the result at the
smaller of 12 and the remaining 40-claim capacity. It does not fill a minimum
or arbitrary quota. Every projected skill has empty hidden evidence and one
exact server-owned claim using the selected fact ID. A revision-declared skill
is valid only for CV `coreSkills`; it cannot support CV narrative or
cover-letter prose. Demonstrated career evidence may support naturally woven
cover-letter skill references, but quality policy `1.4.0` rejects a literal
skills list. Legacy input continues to use `PROFILE.SKILL` records and retains
its historical minimum-fill behaviour. This is one local validation pass, not
a provider retry.

Immutable release `1.5.6` is the immediate rollback and retains schema `3.7.0`,
evaluation policy `1.5.4`, parser `3.4.0`, fixed skill reservation and claim
policy `2.11.0`. Release `1.5.7` is preserved in version-control history but is
not packaged or included in the approved index because it shares schema `3.8.0`
but encodes the previous minimum-eight semantics, which schema-derived runtime
routing cannot distinguish safely.
Release `1.5.5` remains an approved deeper compatibility
release with schema `3.6.0`, parser `3.3.0` and claim policy `2.10.0`.

The local parser also caps every text node at 4,000 characters and every array
at 40 items. These parser-wide limits are defence in depth and keep approved
legacy rollback bundles bounded even though their structural schemas were
compiled from output exemplars.

## Validation and safe failure

At request construction time, the service binds the exact model-facing
approved evidence IDs into `$defs.approvedEvidenceId`. Both ordinary claims and
the dedicated personal-summary claim reference that enum. The provider is
therefore constrained to IDs present in the same approved-evidence catalogue
it receives as input; parser `3.5.2` resolves and enforces the local reference
again before claim validation.

Validation runs before rendering, document storage, application creation or
billing commit:

1. reject blank or over-limit raw output;
2. parse one exact JSON value with duplicate-field and trailing-token
   detection—Markdown fences are not repaired or removed;
3. validate required and unknown fields, types, enums, array counts and string
   patterns against the exact requested schema;
4. reject raw or HTML-encoded markup, active URI schemes, event-handler
   attributes and unsafe control characters from every text node;
5. for schemas `3.5.0` through `3.8.0`, bind the validated private envelope and
   project its two exact canonical application siblings into the unchanged
   public claims ledger shape; schema `3.8.0` additionally projects its exact
   `CLAIM-9003` personal-summary sibling;
6. require every non-empty final claim-bearing path exactly once in the claim
   ledger and validate its disposition, approved evidence IDs, atomic facts,
   numeric claims and high-risk claim categories;
7. for project-aware schemas `3.2.0` through `3.8.0`, enforce canonical titles
   and correspondence boilerplate, unique skills and narrative,
   qualification-once, selected-entry coverage, category-correct structured
   projects and employment-only work history;
8. for schemas `3.4.0` through `3.8.0`, require the exact canonical opening and
   closing as isolated canonical-evidence claims and require confirmed claimant
   evidence for every other non-identity versioned claim. Schemas `3.5.0`
   through `3.8.0` additionally enforce bookend isolation in the raw provider
   shape, and schemas `3.6.0` through `3.8.0` reject non-empty per-skill
   evidence;
9. for active schema `3.8.0`, require the dedicated claimant-evidenced personal
   summary, clear only an unclaimed optional `tailoredDescription`, attach the
   two canonical title paths deterministically, project at most 12 exact
   selected versioned skills without filling a minimum, and reject literal
   cover-letter skill lists.

The renderer then omits empty sections, presents project-only CVs as Technical
Profile, Projects, Technical Skills and Education and Qualifications, and adds
a deterministic visible application line built from the ledger-owned canonical
job title and company. Generic correspondence always uses `Dear Hiring
Manager`, the two exact canonical application bookends and `Yours faithfully`.

Failure messages contain only a JSON path and policy reason. They do not retain
parser exceptions that may contain model-output fragments. The parser never
repairs or splits an invalid canonical claim and generation is not retried.
Any failure releases the billing reservation; tests prove there is no document
save, application creation or charge commit after invalid output.

## Version evidence

Successful response metadata and structured logs record the prompt release,
template, rules and schema versions and their hashes. Request/response logs
also record LLM contract `2.0`, the schema-applied parser version, claim policy
version, the actual model ID and the gateway-owned deployment, admission and
pricing-policy versions. Active release `1.5.11` records parser `3.6.0`, claim
policy `2.14.0` and quality policy `1.4.0`; immediate rollback schema `3.7.0`
records parser `3.4.0` and claim policy `2.11.0`; schema `3.6.0` records parser
`3.3.0` and claim policy `2.10.0`; selecting release `1.5.3` retains parser
`3.2.0`. The consumer accepts gateway audit fields only from the mandatory
audit block in the reviewed gateway contract pinned at `c0a2eb1`; missing or
malformed audit evidence fails before document storage, application creation
or billing commit. The exact model ID is also written to the existing Payment
commit record.

Durable attachment of the complete claim and generation provenance to an
immutable stored document remains dependent on DOC-06 and is outside
DOCGEN-08.

All repository tests use synthetic fixtures and mocked downstreams. They make
no live or paid provider request and use no real job-seeker data or production
credentials.

See [`CLAIM_EVIDENCE_POLICY.md`](CLAIM_EVIDENCE_POLICY.md) for the catalogue,
disposition and fail-closed rules. Prompt release `1.5.6` is the immutable
immediate approved emergency rollback. Claim-ledger validation is applied when the
selected schema contains `claims`; the `1.5.x` quality policy is applied only
when the selected schema also contains the governed `cv.projects` shape.
