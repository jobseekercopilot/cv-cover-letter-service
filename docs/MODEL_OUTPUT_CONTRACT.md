# Model output contract

CV and Cover Letter Service treats model output as untrusted until it passes
the exact reviewed schema and local safe-content policy. The active prompt
release is `cv-cover-letter-1.5.1`; it owns output schema `3.2.0` at
`src/main/resources/prompts/bundles/cv-cover-letter-1.5.1/output-schema.json`.
Its reviewed SHA-256 is
`036ce33ef517c0c4b7cca5fa4467f31f9ecc85e90f98504a430162cc844a2998`.

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
| Skills | 0–12 items; quality policy requires up to 8 confirmed skills when available |
| Projects | 10 entries; 8 highlights per project |
| Qualifications and work-history entries | 20 items each |
| Responsibilities | 12 per work-history entry |
| Generation-note lists | 20 items; 500 characters per item |
| Claim ledger | 1–40 claims; 30 evidence IDs and final paths per claim |

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
5. bind the validated tree to the domain DTO;
6. require every non-empty final claim-bearing path exactly once in the claim
   ledger and validate its disposition, approved evidence IDs, atomic facts,
   numeric claims and high-risk claim categories;
7. for schema `3.2.0`, enforce canonical titles and correspondence boilerplate,
   unique skills and narrative, qualification-once, selected-entry coverage,
   category-correct structured projects and employment-only work history.

The renderer then omits empty sections, presents project-only CVs as Technical
Profile, Projects, Technical Skills and Education and Qualifications, and adds
a deterministic visible application line built from the ledger-owned canonical
job title and company. Generic correspondence always uses `Dear Hiring Manager`
and `Yours faithfully`.

Failure messages contain only a JSON path and policy reason. They do not retain
parser exceptions that may contain model-output fragments. Any failure
releases the billing reservation; tests prove there is no document save,
application creation or charge commit after invalid output.

## Version evidence

Successful response metadata and structured logs record the prompt release,
template, rules and schema versions and their hashes. Request/response logs
also record LLM contract `2.0`, parser `3.2.0`, claim policy `2.8.0`, the
actual model ID and the gateway-owned deployment, admission and pricing-policy
versions. The consumer accepts those fields only from the mandatory audit block
in the reviewed gateway contract pinned at `c0a2eb1`; missing or malformed
audit evidence fails before document storage, application creation or billing
commit. The exact model ID is also written to the existing Payment commit
record.

Durable attachment of the complete claim and generation provenance to an
immutable stored document remains dependent on DOC-06 and is outside
DOCGEN-08.

All repository tests use synthetic fixtures and mocked downstreams. They make
no live or paid provider request and use no real job-seeker data or production
credentials.

See [`CLAIM_EVIDENCE_POLICY.md`](CLAIM_EVIDENCE_POLICY.md) for the catalogue,
disposition and fail-closed rules. Prompt release `1.5.0` remains the immediate
approved emergency rollback. Claim-ledger validation is applied when the
selected schema contains `claims`; the `1.5.x` quality policy is applied only
when the selected schema also contains the governed `cv.projects` shape.
