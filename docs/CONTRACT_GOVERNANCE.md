# Producer contract governance

CV and Cover Letter Service consumes four cross-service boundaries. It
generates the three Java clients used by its source from reviewed contracts
during Maven `generate-sources`. Payment uses a handwritten `RestClient`
adapter, so its current reserve/commit/release boundary is pinned and checked
without generating an unused fourth client. No compiled client JAR is committed
or loaded from `libs/`.

## Current pins

| Producer | Revision | Contract | Version | SHA-256 | Use |
| --- | --- | --- | --- | --- | --- |
| `jobseekercopilot/llm-gateway` | `f91ab264723aa97809990aedfe29d9be77fa68d9` | `contracts/openapi.json` | `1.0.0` | `0adce79ec309bf506da125fb423eacf329cc4bc4061e01ee4b9eefd33b041555` | Generated Java client |
| `jobseekercopilot/document-store-service` | `fedcdbdec63795269c4e4c4f43fc32f38c6327b1` | `contracts/openapi.json` | `1.0.0` | `410ab1a7a2e8a5a5ad374443ec834f6aef7f778f6936b3ef90c33f8e580cdbd9` | Generated Java client |
| `jobseekercopilot/application-tracker-service` | `d9e6bc9fcbe4ef665334c58672c8062b1e4796aa` | `contracts/openapi.json` | `1.1.0` | `549cebba300c2caf3403b9de01d3c34de84464a280a02183751e8a9583ad982a` | Generated Java client |
| `jobseekercopilot/payment-service` | `3175e5730cd0743e15455a0acc8e2bc35b56a78f` | `contracts/openapi.json` | `1.0.0` | `2b1bfef95e1ba4c1f191627dfc4972b3ed7a931dead8fbb7aecbf5b793acae7a` | Handwritten adapter compatibility |

OpenAPI Generator `7.5.0` with the Java `resttemplate` library generates into
`target/generated-sources`. The `.SOURCE` files record provenance and
`SHA256SUMS` protects the reviewed bytes. Generated sources and binaries are
disposable build outputs.

## Compatibility boundaries

The policy checks the exact operations and fields currently consumed:

- LLM generation request, response text and usage;
- Document Store create request and returned document ID;
- Application Tracker create request, returned application ID and required
  service-token security scheme;
- Payment reservation, commit and release paths, owner header and payloads.

The pinned Application Tracker contract does not mean the current adapter
supplies its required producer token or owner context. CVCL-02 owns that direct
beta blocker. The pinned Payment contract likewise does not approve its current
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
