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

The public producer contract is now `3.1.0`. Generation requires the
`serviceToken` API-key scheme in `X-Service-Token`, one required
`X-Document-Owner` header and bounded provenance-aware input snapshot `1.0`.
The additive `generationMetadata` response identifies the immutable prompt
bundle, template, rules, output schema and evaluation policy without exposing
prompt or source payloads. Consumers must authenticate the user and bind the
resulting stable subject to the owner header. The immutable Java coordinate and
source revision are recorded in `api/client-release.json`:
`com.jobseekercopilot.clients:cv-cover-letter-service-client:3.1.0-rev.f2c5916fa0da`.

## Current pins

| Producer | Revision | Contract | Version | SHA-256 | Use |
| --- | --- | --- | --- | --- | --- |
| `jobseekercopilot/llm-gateway` | `c0a2eb1fa7adb437cf5cda10a491112108619f66` | `contracts/openapi.json` | `2.0.0` | `d45bf93cdadf181b9de387aa6358690fc1c83b4543e47934a2d1380e48dd16ec` | Generated Java client |
| `jobseekercopilot/document-store-service` | `b696fe81e9b900e0749e185f595ff4c98c24119d` | `contracts/openapi.json` | `1.1.0` | `3d0595c83cc66d9037e08af6a4b087c115c9a5d99ec71491f1aa5fc3afffd6ba` | Generated Java client |
| `jobseekercopilot/application-tracker-service` | `d9e6bc9fcbe4ef665334c58672c8062b1e4796aa` | `contracts/openapi.json` | `1.1.0` | `549cebba300c2caf3403b9de01d3c34de84464a280a02183751e8a9583ad982a` | Generated Java client |
| `jobseekercopilot/payment-service` | `3175e5730cd0743e15455a0acc8e2bc35b56a78f` | `contracts/openapi.json` | `1.0.0` | `2b1bfef95e1ba4c1f191627dfc4972b3ed7a931dead8fbb7aecbf5b793acae7a` | Handwritten adapter compatibility |

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
- Payment reservation, commit and release paths, owner header and payloads.

The Document Store and Application Tracker adapters supply distinct runtime
producer credentials. Store creates include the trusted inbound owner once in
`X-Document-Owner`; Tracker creates bind that same owner in the producer-only
request body. The pinned Payment contract does not approve its current
caller-controlled identity, atomicity or idempotency semantics. Those remain
owned by CVCL-02 and the Payment beta workstream.

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
