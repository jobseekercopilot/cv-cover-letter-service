# Gateway authorization boundary

The CV and Cover Letter Service trusts one caller: the Document Generation
Gateway. It does not authenticate an end-user bearer token and it does not
accept user-selected document ownership.

## Inbound identity

For every request below `/api/v1/cv-cover-letter/`, the Gateway must provide:

- exactly one `X-Service-Token` matching the dedicated
  `CV_COVER_LETTER_GATEWAY_TOKEN` runtime secret;
- exactly one non-blank `X-Document-Owner` containing the stable subject from
  the Gateway's already-authenticated user identity.

The configured service token must contain at least 32 UTF-8 bytes. Missing or
weak configuration prevents startup. Missing, invalid, combined, or duplicate
service credentials return `401` with
`SERVICE_AUTHENTICATION_REQUIRED`. Missing, blank, combined, or duplicate owner
context returns `400` with `DOCUMENT_OWNER_REQUIRED`. These errors do not
reflect credential values.

The trusted owner is passed separately into generation and downstream calls;
the bounded request body contains no owner or user ID. The obsolete
`X-User-Id` header and legacy body identity fields cannot select another
user's records.

Use a separate secret for this boundary; do not reuse credentials for other
services. Inject it at runtime, keep it out of images and source control, and
rotate the Gateway and this service together.

## Outbound identities

The service uses three further runtime credentials:

- `DOCUMENT_STORE_PRODUCER_TOKEN` authenticates only Document Store producer
  calls. Each create also sends the trusted inbound subject exactly once as
  `X-Document-Owner`; the request-body user ID is set from the same subject.
- `APPLICATION_TRACKER_PRODUCER_TOKEN` authenticates only Application Tracker
  producer calls. Application creation binds the trusted inbound subject to
  the request owner. This role cannot change status, withdraw or delete.
- `CV_COVER_LETTER_TO_PAYMENT_SERVICE_TOKEN` authenticates only Payment
  Service reservation, commit, release and lifecycle lookup calls. Each call
  sends the trusted inbound subject as `X-Payment-Owner`; `X-User-Id` is never
  sent. One service-created operation key is reused for bounded reservation
  retries and is not accepted from the caller.

All credentials must contain at least 32 UTF-8 bytes and must be distinct
from each other and from `CV_COVER_LETTER_GATEWAY_TOKEN`. Missing, weak or
reused values prevent startup. Credentials are never accepted from a request,
written to logs or embedded in a generated client.

## Remaining CVCL-02 work

Infrastructure runtime secret wiring/rotation and fleet-level negative tests
are still required before beta. Whole-generation duplicate suppression remains
in the orchestration workstream; Payment operation retry and compensation
recovery are now implemented at this boundary. User approval, prompt safety and
output quality remain in their existing workstreams.
