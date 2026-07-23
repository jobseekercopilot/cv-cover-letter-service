# CV and Cover Letter Service

Domain service that builds the CV/cover-letter prompt, requests generation from
`llm-gateway`, validates and renders the response, stores both documents, and
creates an application record.

This migration baseline is **not beta-ready**. The current build depends on
untracked `systemPath` client JARs, generated claims are not traceable to source
facts, the prompt is vulnerable to instructions in untrusted content, and
documents are persisted before user approval. See
[`docs/BETA_READINESS_AUDIT.md`](docs/BETA_READINESS_AUDIT.md).

## Technology

- Java 17
- Spring Boot 3.2.0
- Maven

## API contract

[`contracts/openapi.json`](contracts/openapi.json) is the migration-time
OpenAPI snapshot. Contract publication and reproducible client generation are
tracked as beta blockers.

## Build

```bash
mvn -B clean verify
```

The command currently fails in a clean clone because generated service clients
are referenced from an untracked local `libs/` directory. Compiled clients must
not be committed as the fix.

## Safe local use

Use synthetic fixtures and the deterministic provider mode. Do not use real
CVs, cover letters, profiles, job-seeker data, model credentials, or paid model
requests while this service remains pre-beta.

## Licence

Copyright © 2026 Bernard McGeever. All rights reserved.

This repository contains proprietary software belonging to Bernard McGeever.
It may not be used, copied, modified or distributed without express written
permission. See [LICENSE](./LICENSE).
