# Claim evidence policy

Prompt release `cv-cover-letter-1.5.3`, output schema `3.4.0`, evaluation
policy `1.5.1`, claim policy `2.10.0` and deterministic quality policy `1.1.0`
establish the current claim-provenance and document-quality boundary.

Release `1.5.1` makes the provider perform an explicit final-pointer coverage
audit, including generic narrative and each populated array index, before it
returns the claim ledger. Release `1.5.2` makes the provider ledger
final-content-only: every entry is `SUPPORTED` or `REWORDED`, has at least one
approved evidence ID and final content path, and has empty review text.
Unsupported and unconfirmed material is omitted from the documents and ledger;
only a neutral missing-information note may remain. The broader four-
disposition local and published contract is unchanged for historical and
rollback output. Local validation remains fail-closed. If the model
omits only a required project-description pointer, policy `2.10.0` replaces
that prose with the unique exact `DESCRIPTION` fact from the PROJECT selection
anchored by the same project's exact claimed `HEADING`; it then creates exact
claim coverage. If a versioned response omits only an opening or closing
cover-letter pointer, the same policy discards the unaccounted model prose and
uses fixed, server-owned application correspondence without interpolating
untrusted job text. This narrow local recovery is accepted without claimant
evidence only when the final path and complete text match exactly and the
ledger cites exactly the canonical generation-intent, job-title and company
records. Under approved rollback and historical schemas, the fixed text is not
reserved: a model-authored claim with additional confirmed claimant evidence
follows the ordinary evidence rules. The active `1.5.3` schema and provider
contract instead require the isolated canonical claims described below.
Claimed unsafe prose, missing claimant evidence outside the exact exception,
ambiguous project evidence and every other omitted narrative still reject.

Release `1.5.3` makes that exception explicit at both provider boundaries.
`/coverLetter/openingParagraph` must be exactly `Please consider my application
for this role.` and `/coverLetter/closingParagraph` must be exactly `Thank you
for considering my application.`. Each canonical bookend must be covered by
its own `SUPPORTED` claim: `evidenceIds` is exactly
`["REQUEST.GENERATION_INTENT", "JOB.TITLE", "JOB.COMPANY"]`, `contentPaths`
contains only that bookend's pointer, and `reviewText` is empty. Neither
bookend claim may include another final path or claimant evidence. Canonical
job and request evidence may supplement ordinary claims but, outside the
bounded identity fields and these two isolated bookends, at least one
purpose-compatible confirmed claimant snapshot fact is mandatory.

Policy `2.10.0` also isolates each versioned project claim after evidence
enrichment. The project paths retain snapshot facts only from their unique,
exact-title-anchored PROJECT selection; paths outside that project are split
into separate claims. This prevents a qualification or another project
selection from lending facts to the rendered project. Unknown evidence IDs
and non-snapshot request or job context are retained for ordinary fail-closed
validation. Missing or ambiguous title anchors are never guessed. Submitted
claim paths must identify the known claim-bearing topology in the
schema-validated output tree. A pointer to an existing but empty optional
field or empty container may accompany a populated pointer and is discarded
because it cannot cover final content. An empty-only final claim, server-owned
non-claim field, missing, unknown or out-of-range pointer rejects. Submitted
evidence IDs are validated as approved and unique before empty pointers can
be removed. Exact-once coverage of all populated final content remains
mandatory after canonicalisation.

Policy `2.10.0` also repairs one narrowly identifiable structural provider
error for versioned evidence: when every submitted final cover-letter body
pointer, as an exact multiset, is the complete canonical one-based sequence
`/coverLetter/bodyParagraphs/1` through the actual paragraph count, each
pointer is shifted to its corresponding zero-based JSON Pointer. The pointer
shift itself leaves claim ownership, disposition and submitted evidence IDs
unchanged; ordinary deterministic evidence enrichment and the complete
topology, evidence-purpose, content-alignment and exact-once checks still run.
The repair never shifts legacy evidence, another array, a partial, duplicate,
mixed, leading-zero, malformed or review-only sequence, or a container mixed
with leaf paths. Malformed and out-of-range paths reject at topology
validation; otherwise structurally valid unchanged paths continue through the
ordinary evidence, canonicalisation and exact-coverage policy. A valid
zero-based ledger and the existing container-only compatibility form are
unchanged.

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

The published and local claim-ledger contract retains four dispositions:

- `SUPPORTED`: exact or directly composed from cited approved evidence;
- `REWORDED`: wording changed without changing the cited evidence's meaning;
- `CONFIRMATION_REQUIRED`: useful candidate material that requires user review;
- `REJECTED`: unsupported material excluded from the documents.

The active `1.5.3` provider schema emits only supported and reworded entries.
They require approved evidence IDs and one or more exact JSON Pointer paths
into final CV or cover-letter text, and their `reviewText` must be empty.
Confirmation-required and rejected entries remain accepted only through
approved rollback or historical local validation; they may contain only review
text and their final `contentPaths` must be empty.

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
7. accepts the fixed opening and closing without claimant evidence only as
   separate exact canonical-evidence claims, and requires confirmed claimant
   evidence for every other non-identity versioned claim;
8. revalidates the complete schema and plain-text policy after every trusted
   canonicalisation.

For the project-aware `3.2.0`, `3.3.0` and `3.4.0` schemas,
`GeneratedDocumentQualityValidator` runs only after the grounding ledger is
normalised and accepted. It:

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
