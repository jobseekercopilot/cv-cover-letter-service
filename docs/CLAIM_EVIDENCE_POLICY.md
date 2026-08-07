# Claim evidence policy

Prompt release `cv-cover-letter-1.5.9`, output schema `3.8.0`, evaluation
policy `1.5.7`, parser `3.5.2`, claim policy `2.13.0` and deterministic quality
policy `1.2.0`
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
omits only a required project-description pointer, the active policy replaces
that prose with the unique exact `DESCRIPTION` fact from the PROJECT selection
anchored by the same project's exact claimed `HEADING`; it then creates exact
claim coverage. If a versioned response omits only an opening or closing
cover-letter pointer, the same policy discards the unaccounted model prose and
uses fixed, server-owned application correspondence without interpolating
untrusted job text. This narrow local recovery is accepted without claimant
evidence only when the final path and complete text match exactly and the
ledger cites exactly the canonical generation-intent, job-title and company
records. Under approved historical schemas before dedicated canonical claims,
the fixed text is not reserved: a model-authored claim with additional
confirmed claimant evidence follows the ordinary evidence rules. The
historical `1.5.3` contract requires the isolated canonical claims described
below; release `1.5.5` retains their structural isolation and exact-empty skill
evidence. Immediate rollback release `1.5.6` adds the exact ordinary-path
allowlist and fixed-budget deterministic skill projection. Historical `1.5.7`
adds the dedicated personal-summary claim, deterministic title coverage,
unclaimed optional-description clearing and adaptive minimum-eight skill
budget described below. Active `1.5.9` retains that wire shape while replacing
the versioned skill policy with job-targeted selection without a minimum or
quota.
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

Release `1.5.4` moves those two claims out of the ordinary provider array and
into the private `canonicalApplicationClaims.opening` and
`canonicalApplicationClaims.closing` siblings. The schema fixes their IDs to
`CLAIM-9001` and `CLAIM-9002`, their disposition to `SUPPORTED`, their
`reviewText` to empty, and each `contentPath` to its one corresponding
bookend. The canonical evidence is represented as three required singleton
scalar fields: `generationIntentEvidenceId`, `jobTitleEvidenceId` and
`companyEvidenceId`. Ordinary claims exclude both bookend paths and the 9xxx
ID range and are capped at 38, reserving the two remaining entries within the
existing 40-claim public bound.

Immediate rollback parser `3.4.0` first validates the complete raw provider
envelope against schema `3.7.0`, then deterministically projects the two
dedicated siblings into the unchanged public claims ledger. The projection
changes only the wire shape: it does not split or repair a grouped ordinary
claim and it does not retry generation. Any absent, grouped, duplicated or
malformed bookend claim fails closed before persistence. The historical
`1.5.3` release continues to use its ordinary-array wire shape and records
applied parser metadata `3.2.0`. Rollback claim policy `2.11.0` validates the
projected ledger. Schema `3.7.0` retains the exact-empty
`coreSkills.evidence` constraint and restricts ordinary `contentPaths` to the
exact CV and cover-letter claim-bearing leaves. It names
`qualificationName`, never `qualificationTitle`, and excludes bookends,
containers, all core-skill paths and hidden evidence. Its ordinary array is
capped at 26, reserving two public entries for canonical claims and up to 12
for projected skill provenance within the unchanged 40-claim public bound.

Schema `3.8.0`, shared by historical release `1.5.7` and active release
`1.5.8`, requires a third private sibling,
`personalSummaryClaim`. It fixes `claimId` to `CLAIM-9003`, `contentPath` to
`/cv/personalSummary` and `reviewText` to empty; its disposition is
`SUPPORTED` or `REWORDED` and its one to 30 evidence IDs must include confirmed
CV-purpose claimant evidence supporting the complete summary. Ordinary claims
exclude this path and `/cv/title` and `/coverLetter/title`, and are capped at
29. Parser `3.5.2` rejects an absent or malformed sibling in the raw envelope
and projects all three private siblings before persistence.

Policy `2.12.0` first normalises the historical `1.5.7` non-skill ledger. It
attaches each canonical title path to a same-purpose identity claim that cites
`JOB.TITLE`;
when no compatible claim exists, it creates a bounded server-owned one-path
identity claim. It retains a non-empty optional work-history
`tailoredDescription` only when an accepted ordinary claim owns the exact path
and otherwise clears it before final exact-coverage validation. Claimed
descriptions receive the normal evidence and content checks and are never
silently cleared as a substitute for validation.

That policy then replaces `cv.coreSkills` with a deterministic projection. It
preserves unique, exact model-selected approved skills, discards unsupported
and duplicate proposals, fills in catalogue order to `min(8, available)`, and
forces every `evidence` field to empty. It calculates capacity after non-skill
normalisation and caps accepted proposals at the smaller of 12 and the
remaining capacity within the 40-claim public limit; insufficient capacity for
`min(8, available)` rejects. A
versioned catalogue contributes only `EVIDENCE_SNAPSHOT` records with purpose
`CV` and fact type `DEMONSTRATED_SKILL`; a legacy catalogue contributes only
`PROFILE.SKILL` records. Model-authored skill-path coverage is rejected by the
schema before claim validation. The exact-coverage stage creates one claim per
projected skill using the exact selected fact ID in projected order. The
provider is called once; projection is not a retry or a weakening of schema,
evidence, topology or exact-once validation.

Active policy `2.13.0` retains the `3.8.0` non-skill normalisation and rebuilds
`cv.coreSkills` from exact, approved, CV-only candidates. For versioned input,
the candidate set is the union of CV-purpose `EVIDENCE_SNAPSHOT`
`DEMONSTRATED_SKILL` records representing demonstrated career wins and the
exact values of revision-bound `PROFILE_REVISION` `DECLARED_SKILL` records. It
preserves unique exact job-targeted model selections, discards unsupported,
duplicate and job-advert-only proposals, prefers demonstrated support for a
normalised duplicate, and caps the result at the smaller of 12 and the
remaining capacity within the 40-claim public limit.
It fills no minimum or quota. Every accepted skill is service-projected with
empty hidden evidence and one exact skill-path claim using the selected fact
ID. A `PROFILE_REVISION` `DECLARED_SKILL` ID is valid only for this CV skill
projection: it cannot support model-authored CV narrative or cover-letter
prose. Demonstrated career evidence may support naturally woven cover-letter
skill references. Legacy positional profile input retains its historical
minimum-fill compatibility behaviour.

Release `1.5.9` sends declared-skill values as dedicated service-projected
core-skill candidates without their stable IDs. The complete internal catalogue
retains those IDs for deterministic projection and final provenance, while the
model-facing approved-evidence catalogue contains only IDs the model may cite.
Those exact IDs are also bound into the strict output schema as one shared enum
referenced by ordinary and personal-summary claim evidence arrays. Unknown or
internal-only IDs are therefore excluded at provider generation time and remain
fail-closed in parser and claim validation.

Version routing remains schema-bound; a release is excluded when a shared
schema cannot safely distinguish its semantics. Active release `1.5.9` uses
schema `3.8.0`, evaluation policy `1.5.7`, parser `3.5.2`,
claim policy `2.13.0` and quality policy `1.2.0`. Immutable immediate rollback
`1.5.6` retains schema `3.7.0`, evaluation policy `1.5.4`, parser `3.4.0` and
claim policy `2.11.0`. Release `1.5.7` is preserved in version-control history
but is not packaged or included in the approved index because it shares schema
`3.8.0` with `1.5.8` while encoding the older minimum-eight semantics;
schema-derived policy routing cannot select it safely. Its historical metadata
remains evaluation policy `1.5.5`, parser `3.5.2`, claim policy `2.12.0` and
quality policy `1.1.0`. Release `1.5.5`
retains schema `3.6.0`, parser `3.3.0` and claim policy `2.10.0`; release
`1.5.3` retains parser `3.2.0`. Selecting a historical schema never opts it
into active normalisation behaviour.

Policy `2.13.0` retains the `2.12.0` and `2.11.0` project-claim isolation after
evidence enrichment. The project paths retain snapshot facts only from their unique,
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

Policy `2.13.0` also retains policy `2.12.0` and `2.11.0`'s narrowly bounded
`2.10.0` repair for one structural provider error for versioned evidence: when
every submitted final cover-letter body
pointer, as an exact multiset, is the complete canonical one-based sequence
`/coverLetter/bodyParagraphs/1` through the actual paragraph count, each
pointer is shifted to its corresponding zero-based JSON Pointer. The pointer
shift itself leaves claim ownership, disposition and submitted evidence IDs
unchanged; ordinary deterministic evidence enrichment and the complete
topology, evidence-purpose, content-alignment and exact-once checks still run.
The same exact-sequence repair is applied independently to each populated
versioned nested text array at `/cv/projects/{i}/highlights` and
`/cv/workHistory/{i}/responsibilities`; the parent object index is never
shifted. Duplicate ownership of a complete one-based sequence is shifted and
then handled by the ordinary exact-once duplicate-coverage normalizer. The
repair never shifts legacy evidence, another array, a partial, mixed,
leading-zero, malformed or review-only sequence, or a
container mixed with leaf paths. Malformed and out-of-range paths reject at
topology validation; otherwise structurally valid unchanged paths continue
through the ordinary evidence, canonicalisation and exact-coverage policy. A
valid zero-based ledger and the existing container-only compatibility form are
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

The active `1.5.9` provider schema emits only supported and reworded ordinary
entries, the required supported-or-reworded personal-summary sibling and the
two structurally fixed supported canonical application siblings. Ordinary
claims require approved evidence IDs and one or more exact JSON Pointer paths
into final CV or cover-letter text, and their `reviewText` must be empty. The
personal-summary sibling has the same final dispositions but one fixed scalar
path; all three private siblings are projected into the unchanged public claim
shape.
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
7. accepts the fixed opening and closing without claimant evidence only after
   their exact dedicated provider siblings have been projected as separate
   canonical-evidence claims, requires the dedicated personal-summary claim to
   contain confirmed CV-purpose claimant evidence, and requires confirmed
   claimant evidence for every other non-identity versioned claim;
8. attaches canonical document-title paths only to same-purpose `JOB.TITLE`
   identity evidence and clears only an optional `tailoredDescription` that no
   accepted ordinary claim owns;
9. allocates active skill claims only after non-skill normalisation, selects at
   most 12 exact job-targeted candidates within the 40-claim limit, prefers
   demonstrated evidence for duplicates and applies no minimum or quota;
10. revalidates the complete schema and plain-text policy after every trusted
   canonicalisation.

The active path contract prevents ordinary claims from owning document-title,
personal-summary, canonical-bookend, core-skill or hidden evidence paths. Skill
and title coverage added by the service remains subject to the same purpose,
exact-fact, unique-ID and exact-once coverage checks. Evidence
IDs remain opaque strings in the immutable schema; request-specific IDs and
all free-text evidence values stay in the untrusted catalogue rather than
being injected into the trusted schema.

For the project-aware `3.2.0` through `3.8.0` schemas,
`GeneratedDocumentQualityValidator` runs only after the grounding ledger is
normalised and accepted. It:

1. requires canonical job titles, company, generic greeting and UK sign-off;
2. limits skills to 12, rejects normalised duplicates, applies no minimum or
   quota, and rejects a literal skills list in the cover letter;
3. rejects duplicate normalised narrative and repeated substantive
   qualification phrases, including phrases embedded in longer paragraphs;
4. requires every selected evidence entry to own at least one final path for
   its document purpose;
5. requires CV project, employment and education selections to appear in their
   governed sections, and requires each project to use one selected PROJECT
   entry;
6. requires every project description and highlight to cite same-selection
   project narrative evidence.

Project facts cannot satisfy the atomic employment fields. An optional
employment narrative without an accepted claim is cleared rather than being
replaced with an arbitrary evidence fact; a claimed narrative must pass the
ordinary evidence checks. The active renderer omits every unsupported empty
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
