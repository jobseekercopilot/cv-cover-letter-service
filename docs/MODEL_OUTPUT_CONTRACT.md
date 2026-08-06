# Model output contract

CV and Cover Letter Service treats model output as untrusted until it passes
the exact reviewed schema and local safe-content policy. The active prompt
release is `cv-cover-letter-1.5.5`; it owns output schema `3.6.0` and
evaluation policy `1.5.3` at
`src/main/resources/prompts/bundles/cv-cover-letter-1.5.5/`. Its reviewed
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
| Cover-letter body | 3–5 body paragraphs; 3,000 characters per paragraph |
| Skills | 0–12 items; quality policy requires up to 8 confirmed skills when available; private `evidence` is exactly empty |
| Projects | 10 entries; 8 highlights per project |
| Qualifications and work-history entries | 20 items each |
| Responsibilities | 12 per work-history entry |
| Generation-note lists | 20 items; 500 characters per item |
| Ordinary provider claims | 1–38 claims; 30 evidence IDs and final paths per claim |
| Canonical provider claims | Exactly two fixed sibling objects |
| Projected public claim ledger | 3–40 claims |

The active `1.5.5` provider ledger contains only `SUPPORTED` and `REWORDED`
final-content claims. Each ordinary claim requires at least one approved
evidence ID and one final path, and `reviewText` is exactly empty. Unsupported
or unconfirmed material is omitted from both the documents and claims; a neutral
`generationNotes.missingInformation` item may describe genuinely absent
information. The published ledger DTO and local rollback validator retain all
four dispositions for historical compatibility.

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
prose is neither accepted nor silently discarded.

After the raw provider envelope passes schema and active-content validation,
parser `3.3.0` deterministically projects the two canonical siblings into the
ordinary claim shape. The resulting union is passed through the existing
fail-closed claim validator and returned through the unchanged public claims
ledger DTO. This is a projection of already validated fixed fields, not a
split, repair or retry path; grouped, missing or malformed canonical claims
are rejected.

The local parser also caps every text node at 4,000 characters and every array
at 40 items. These parser-wide limits are defence in depth and keep approved
legacy rollback bundles bounded even though their structural schemas were
compiled from output exemplars.

## Validation and safe failure

Validation runs before rendering, document storage, application creation or
billing commit:

1. reject blank or over-limit raw output;
2. parse one exact JSON value with duplicate-field and trailing-token
   detection—Markdown fences are not repaired or removed;
3. validate required and unknown fields, types, enums, array counts and string
   patterns against the exact requested schema;
4. reject raw or HTML-encoded markup, active URI schemes, event-handler
   attributes and unsafe control characters from every text node;
5. for schemas `3.5.0` and `3.6.0`, bind the validated private envelope and project their two
   exact canonical siblings into the unchanged public claims ledger shape;
6. require every non-empty final claim-bearing path exactly once in the claim
   ledger and validate its disposition, approved evidence IDs, atomic facts,
   numeric claims and high-risk claim categories;
7. for project-aware schemas `3.2.0`, `3.3.0`, `3.4.0`, `3.5.0` and `3.6.0`, enforce
   canonical titles and correspondence boilerplate, unique skills and
   narrative, qualification-once, selected-entry coverage, category-correct
   structured projects and employment-only work history;
8. for schemas `3.4.0`, `3.5.0` and `3.6.0`, require the exact canonical opening and
   closing as isolated canonical-evidence claims and require confirmed
   claimant evidence for every other non-identity versioned claim. Schema
   `3.5.0` and `3.6.0` additionally enforce that isolation in the raw provider
   shape; `3.6.0` also rejects any non-empty per-skill evidence prose.

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
`2.10.0`, the actual model ID and the gateway-owned deployment, admission and
pricing-policy versions. Active schema `3.6.0` and rollback schema `3.5.0`
record parser `3.3.0`; selecting the `1.5.3` rollback retains applied parser
metadata `3.2.0`. The consumer
accepts gateway audit fields only from the mandatory audit block in the
reviewed gateway contract pinned at `c0a2eb1`; missing or malformed audit
evidence fails before document storage, application creation or billing
commit. The exact model ID is also written to the existing Payment commit
record.

Durable attachment of the complete claim and generation provenance to an
immutable stored document remains dependent on DOC-06 and is outside
DOCGEN-08.

All repository tests use synthetic fixtures and mocked downstreams. They make
no live or paid provider request and use no real job-seeker data or production
credentials.

See [`CLAIM_EVIDENCE_POLICY.md`](CLAIM_EVIDENCE_POLICY.md) for the catalogue,
disposition and fail-closed rules. Prompt release `1.5.4` remains the immediate
approved emergency rollback. Claim-ledger validation is applied when the
selected schema contains `claims`; the `1.5.x` quality policy is applied only
when the selected schema also contains the governed `cv.projects` shape.
