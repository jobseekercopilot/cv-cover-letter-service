# Claim evidence policy

Prompt release `cv-cover-letter-1.3.0` and output schema `3.0.0` establish the
DOCGEN-08 claim-provenance boundary.

## Approved evidence

`ClaimEvidenceCatalogFactory` builds a new catalogue from the authenticated,
normalised generation input. Stable IDs identify individual profile skills,
target roles, qualification fields, employment fields, canonical job fields
and the authenticated generation intent. The catalogue excludes owner IDs,
source resource/version IDs and render-only contact details.

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
   employment titles, employers and dates to the appropriate approved record;
6. rejects uncited numeric claims and unsupported high-risk terms covering
   tools, qualifications, motivation, availability, salary and right-to-work.

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
