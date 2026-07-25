# Document-generation input contract

OpenAPI `3.1.0` accepts input snapshot schema `1.0`. The Document Generation
Gateway is the only caller. It authenticates the user, places the stable
subject in `X-Document-Owner`, and assembles the request from authoritative
service responses. The body deliberately has no user-selected owner.

Unknown JSON fields are rejected. Required text must contain plain text after
normalisation. All text has control characters, tags, and complete or
unterminated `script`/`style` blocks removed; repeated whitespace is collapsed.
An `INPUT_TEXT_NORMALIZED` warning identifies each changed field.

## Ownership and limits

Every snapshot carries `provenance.owner`, `resourceId`, `version`, and
`capturedAt`. References are opaque values of at most 128 characters using
letters, digits, `.`, `_`, `:`, `/`, or `-`. Timestamps more than five minutes
in the future are rejected.

| Field | Owner/source | Required | Maximum and validation | Destination |
| --- | --- | --- | --- | --- |
| `inputSchemaVersion` | CV and Cover Letter Service contract | Yes | Exact value `1.0`; 8 characters | Validation only |
| `profile.provenance` | User Profile Service | Yes | Owner must be `USER_PROFILE_SERVICE` | Audit/runtime only |
| `profile.contact.provenance` | Authentication Service | When contact is supplied | Owner must be `AUTHENTICATION_SERVICE` | Audit/runtime only |
| `profile.contact.fullName` | Authentication Service | No | 120 characters | Document rendering only |
| `profile.contact.email` | Authentication Service | No | Valid email; 254 characters | Document rendering only |
| `profile.location` | User Profile Service | No | 160 characters | Document rendering only |
| `profile.skills[]` | User Profile Service | No | 40 items; 100 characters each | Prompt |
| `profile.targetRoles[]` | User Profile Service | No | 20 items; 120 characters each | Prompt |
| `profile.qualifications[]` | User Profile Service | No | 30 items | Prompt |
| `qualificationName` | User Profile Service | Yes per item | 160 characters | Prompt |
| `issuingBody` | User Profile Service | No | 160 characters | Prompt |
| `status` | User Profile Service | No | `IN_PROGRESS` or `COMPLETED` | Prompt |
| `grade` | User Profile Service | No | 80 characters | Prompt |
| `dateAchieved` | User Profile Service | No | `YYYY`, `YYYY-MM`, or `YYYY-MM-DD`; 10 characters | Prompt |
| `expectedCompletion` | User Profile Service | No | `YYYY`, `YYYY-MM`, or `YYYY-MM-DD`; 10 characters | Prompt |
| `profile.employmentHistory[]` | User Profile Service | No | 30 items | Prompt |
| `jobTitle` | User Profile Service | Yes per item | 160 characters | Prompt |
| `employer` | User Profile Service | Yes per item | 160 characters | Prompt |
| `status` | User Profile Service | No | `CURRENT` or `PREVIOUS_ROLE` | Prompt |
| `startDate` | User Profile Service | Yes per item | `YYYY`, `YYYY-MM`, or `YYYY-MM-DD`; 10 characters | Prompt |
| `endDate` | User Profile Service | No | Same date formats or `present`; 10 characters | Prompt |
| `responsibilities` | User Profile Service | No | 4,000 characters | Prompt |
| `job.provenance` | Job Service | Yes | Owner must be `JOB_SERVICE`; resource ID is the canonical job ID | Audit and downstream job reference |
| `job.title` | Job Service | Yes | 160 characters | Prompt and Application Tracker |
| `job.company` | Job Service | Yes | 160 characters | Prompt and Application Tracker |
| `job.location` | Job Service | No | 160 characters | Prompt and Application Tracker |
| `job.employmentType` | Job Service | No | 80 characters | Prompt |
| `job.postedDate` | Job Service | No | ISO `YYYY-MM-DD` | Prompt |
| `job.description` | Job Service | Yes | 12,000 characters | Prompt |

The total normalised prompt-bound text is limited to 40,000 characters.
Trusted owner, contact details, provenance IDs, source versions, and timestamps
never reach prompt construction.

## Missing, duplicate, and conflicting data

Warnings are emitted in stable request-field order and returned in
`inputWarnings`; the same warning list is supplied to the model as evidence it
must not reconstruct:

- missing contact name/email, skills, target roles, qualifications, employment
  history, statuses, responsibilities, or a previous role's end date;
- empty list entries removed during normalisation;
- case-insensitive duplicate skills and target roles;
- duplicate qualifications identified by name and issuer;
- duplicate employment identified by title, employer, and start date;
- conflicting qualification or employment records, where the first entry is
  retained;
- achieved/expected qualification dates inconsistent with status;
- an end date supplied for a current role;
- a job posted date in the future.

Invalid dates and reversed employment date ranges fail the request. A missing
optional value is never inferred. This contract does not approve model
provider retention or training, prompt-injection defences, generated-claim
provenance, or persistence-before-approval; those remain separate beta
workstreams.

## Minimisation rationale

The model receives only employment, qualification, skill, target-role, and
canonical-job evidence needed to tailor content. Full name, email, and profile
location are applied locally by the deterministic renderers. Provenance is
validated and retained for orchestration but is not useful model context.
Salary, commute, URLs, match score, internal database IDs, and broad preference
objects from the legacy DTOs are excluded.
