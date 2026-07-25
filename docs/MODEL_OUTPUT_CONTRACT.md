# Model output contract

CV and Cover Letter Service treats model output as untrusted until it passes
the exact reviewed schema and local safe-content policy. The active prompt
release is `cv-cover-letter-1.2.0`; it owns output schema `2.0.0` at
`src/main/resources/prompts/bundles/cv-cover-letter-1.2.0/output-schema.json`.
Its reviewed SHA-256 is
`ca1b5def221a8205a11bf6925c929fcc47b537dceff733a38f2df5fa9a14990f`.

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
| Cover-letter body | 1–7 paragraphs; 3,000 characters per paragraph |
| Skills | 40 items |
| Qualifications and work-history entries | 30 items each |
| Responsibilities | 20 per work-history entry |
| Generation-note lists | 20 items; 500 characters per item |

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
5. bind the validated tree to the domain DTO.

Failure messages contain only a JSON path and policy reason. They do not retain
parser exceptions that may contain model-output fragments. Any failure
releases the billing reservation; tests prove there is no document save,
application creation or charge commit after invalid output.

## Version evidence

Successful response metadata and structured logs record the prompt release,
template, rules and schema versions and their hashes. Request/response logs
also record LLM contract `2.0`, parser `2.0.0`, the actual model ID and the
gateway-owned deployment, admission and pricing-policy versions. The consumer
accepts those fields only from the mandatory audit block in the reviewed
gateway contract pinned at `c0a2eb1`; missing or malformed audit evidence fails
before document storage, application creation or billing commit. The exact
model ID is also written to the existing Payment commit record.

Durable attachment of the complete generation provenance to an immutable
stored document remains dependent on DOC-06 and is outside DOCGEN-07.

All repository tests use synthetic fixtures and mocked downstreams. They make
no live or paid provider request and use no real job-seeker data or production
credentials.
