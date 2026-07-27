# Producer contract governance

CV and Cover Letter Service consumes four cross-service boundaries. It
generates the three Java clients used by its source from reviewed contracts
during Maven `generate-sources`. Payment uses a handwritten `RestClient`
adapter, so its current reserve/commit/release boundary is pinned and checked
without generating an unused fourth client. No compiled client JAR is committed
or loaded from `libs/`.

The service also owns `contracts/openapi.json` as its public producer contract.
Its Java client is generated independently under `api/client`, with immutable
release metadata, source-revision and checksum provenance, byte-for-byte
generation/package checks, a conservative breaking-change gate and a fresh
authenticated smoke consumer. Publication is restricted to `develop` and
refuses to overwrite an existing coordinate.

The public producer contract is now `3.2.0`. Generation requires the
`serviceToken` API-key scheme in `X-Service-Token`, one required
`X-Document-Owner` header and bounded provenance-aware input snapshot `1.0`.
The additive draft endpoint also requires the Gateway-owned
`X-Generation-Operation-Id`; its response deliberately has no document or
application ID and exposes only bounded content, prompt provenance, model
usage, and provider audit evidence. The estimate endpoint has no provider or
domain side effects. The `3.1.0` generate-and-commit operation is retained only
for the coordinated consumer migration.
The additive `generationMetadata` response identifies the immutable prompt
bundle, template, rules, output schema and evaluation policy without exposing
prompt or source payloads. Consumers must authenticate the user and bind the
resulting stable subject to the owner header. The immutable Java coordinate and
source revision of the last published client are recorded in
`api/client-release.json`. A `3.2.0` client must be published only after this
producer contract is merged; the existing `3.1.0` coordinate remains
immutable.

## Current pins

| Producer | Revision | Contract | Version | SHA-256 | Use |
| --- | --- | --- | --- | --- | --- |
| `jobseekercopilot/llm-gateway` | `c0a2eb1fa7adb437cf5cda10a491112108619f66` | `contracts/openapi.json` | `2.0.0` | `d45bf93cdadf181b9de387aa6358690fc1c83b4543e47934a2d1380e48dd16ec` | Generated Java client |
| `jobseekercopilot/document-store-service` | `b696fe81e9b900e0749e185f595ff4c98c24119d` | `contracts/openapi.json` | `1.1.0` | `3d0595c83cc66d9037e08af6a4b087c115c9a5d99ec71491f1aa5fc3afffd6ba` | Generated Java client |
| `jobseekercopilot/application-tracker-service` | `d9e6bc9fcbe4ef665334c58672c8062b1e4796aa` | `contracts/openapi.json` | `1.1.0` | `549cebba300c2caf3403b9de01d3c34de84464a280a02183751e8a9583ad982a` | Generated Java client |
| `jobseekercopilot/payment-service` | `2fc961c8facc89a334b051e155836423912a2498` | `contracts/openapi.json` | `3.0.0` | `08312957171b34df832b5b3e62ffba93d68007981ac7c284e8b0bff7de22295a` | Authenticated owner-bound adapter with operation-key retries and lifecycle recovery |

OpenAPI Generator `7.5.0` with the Java `resttemplate` library generates into
`target/generated-sources`. The `.SOURCE` files record provenance and
`SHA256SUMS` protects the reviewed bytes. Generated sources and binaries are
disposable build outputs.

## Compatibility boundaries

The policy checks the exact operations and fields currently consumed:

- LLM v2 trust-separated instructions/input, strict output schema, response
  text, provider-neutral usage and mandatory model/deployment/cost audit
  metadata;
- Document Store authenticated owner-scoped create request and returned
  document ID;
- Application Tracker create request, returned application ID and required
  service-token security scheme;
- Payment reservation, commit, release and owner-scoped lifecycle paths,
  service identity, trusted owner, operation key, expiry and recovery evidence.

The legacy Document Store and Application Tracker adapters supply distinct runtime
producer credentials. Store creates include the trusted inbound owner once in
`X-Document-Owner`; Tracker creates bind that same owner in the producer-only
request body. Payment calls use their own runtime service token and the same
trusted inbound owner. Each generation invocation creates one operation key and
reuses it across bounded reservation retries. Ambiguous terminal responses are
resolved through Payment lifecycle state; unresolved compensation is surfaced
as a distinct failure. Whole-generation duplicate suppression remains an
orchestration concern rather than a Payment operation-key guarantee. The new
draft operations do not use any of those three adapters; Gateway-owned
coordination will make them removable after consumer migration.

## Updating a pin

1. Merge and verify the producer change.
2. Record its exact merged `develop` revision and producer-owned contract.
3. Review the API diff for compatibility, identity, privacy and transaction
   impact.
4. Copy the exact producer artifact into `src/main/openapi`.
5. Update its `.SOURCE` file and `SHA256SUMS`.
6. Update policy assertions only when the consumer change is intentional.
7. Run:

   ```bash
   ./scripts/test-contract-policy.sh
   ./scripts/verify-contracts.sh
   mvn -B --no-transfer-progress clean verify
   docker build --tag local/cv-cover-letter-service .
   ```

8. Merge only after pull-request and post-merge `develop` CI pass.

If a producer change is incompatible, retain the previous reviewed pin until
the consumer is ready. Rollback is a normal revert to the previous contract,
source metadata and checksum as one change; never substitute an untracked
generated JAR.

Licence metadata corrections are separate producer changes. LLM Gateway is
tracked by
[BACKLOG-DOCS-03](https://github.com/jobseekercopilot/llm-gateway/issues/7),
Document Store by
[BACKLOG-DOCS-02](https://github.com/jobseekercopilot/document-store-service/issues/19),
and Payment by PAY-16. They are not implemented in this build slice.
