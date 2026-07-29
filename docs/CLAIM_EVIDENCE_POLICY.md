# Claim evidence policy

Prompt release `cv-cover-letter-1.5.1`, output schema `3.2.0`, claim policy
`2.5.0` and deterministic quality policy `1.1.0` establish the current
claim-provenance and document-quality boundary.

Release `1.5.1` makes the provider perform an explicit final-pointer coverage
audit, including generic narrative and each populated array index, before it
returns the claim ledger. Local validation remains fail-closed. If the model
omits only a required project-description pointer, policy `2.5.0` replaces
that prose with the unique exact `DESCRIPTION` fact from the PROJECT selection
anchored by the same project's exact claimed `HEADING`; it then creates exact
claim coverage. Missing, ambiguous or cross-selection evidence still rejects.

## Approved evidence

`ClaimEvidenceCatalogFactory` builds a new catalogue from the authenticated,
normalised generation input. Schema `2.0` uses immutable purpose-bound evidence
snapshots and stable fact UUIDs; the legacy catalogue retains positional profile
facts only for the coordinated compatibility path. Canonical job facts and the
authenticated generation intent remain service-owned. The catalogue excludes
owner IDs and render-only contact details.

The catalogue is server-owned. Model output cannot add evidence records or
alter their IDs. Profile and job values are sent through LLM Gateway's
untrusted-input field, never interpolated into reviewed instructions.

## Claim dispositions

Every claim-ledger entry has one disposition:

- `SUPPORTED`: exact or directly composed from cited approved evidence;
- `REWORDED`: wording changed without changing the cited evidence's meaning;
- `CONFIRMATION_REQUIRED`: useful candidate material that requires user review;
- `REJECTED`: unsupported material excluded from the documents.

Supported and reworded entries require approved evidence IDs and one or more
exact JSON Pointer paths into final CV or cover-letter text. Their `reviewText`
must be empty. Confirmation-required and rejected entries may contain only
review text; their final `contentPaths` must be empty.

## Fail-closed validation

After strict schema and active-content checks, and before rendering or any
external write, `ClaimEvidenceValidator`:

1. rejects unknown or duplicate claim/evidence IDs;
2. requires every non-empty claim-bearing final path exactly once;
3. prevents confirmation-required or rejected claims from pointing at final
   content;
4. requires candidate CV narratives to cite profile evidence rather than job
   requirements or generation intent alone;
5. exact-matches job titles, companies, profile skills, qualification facts,
   project titles/roles/context/dates and employment titles/employers/dates to
   the appropriate approved record;
6. rejects uncited numeric claims and unsupported high-risk terms covering
   tools, qualifications, motivation, availability, salary and right-to-work;
7. revalidates the complete schema and plain-text policy after every trusted
   canonicalisation.

For schema `3.2.0`, `GeneratedDocumentQualityValidator` runs only after the
grounding ledger is normalised and accepted. It:

1. requires canonical job titles, company, generic greeting and UK sign-off;
2. limits skills to 12, rejects normalised duplicates and requires up to 8
   confirmed demonstrated skills when that many are selected;
3. rejects duplicate normalised narrative and repeated substantive
   qualification phrases, including phrases embedded in longer paragraphs;
4. requires every selected evidence entry to own at least one final path for
   its document purpose;
5. requires CV project, employment and education selections to appear in their
   governed sections, and requires each project to use one selected PROJECT
   entry;
6. requires every project description and highlight to cite same-selection
   project narrative evidence.

Project facts cannot satisfy the atomic employment fields. Empty optional
employment narrative remains empty rather than being replaced with an
arbitrary evidence fact. The active renderer omits every unsupported empty
section.

Validation failures expose only a ledger path and policy reason. They release
the billing reservation and occur before document storage, application
creation or billing commit. Logs contain only policy version and record/claim
counts, never source values, generated text or review text.

## Remaining dependency

This slice does not create a user correction UI or mutate approved facts.
DOCGEN-16 must provide the controlled, authenticated and audited review path
that converts a confirmed correction into an approved source record before a
later generation can use it. Durable storage of the complete claim provenance
with immutable documents remains a separate DOC-06 dependency.
