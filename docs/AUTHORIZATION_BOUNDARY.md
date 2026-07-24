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

The trusted owner overwrites `request.userProfile.userId` before generation or
downstream work. The obsolete `X-User-Id` header and a conflicting body user ID
cannot select another user's records.

Use a separate secret for this boundary; do not reuse credentials for other
services. Inject it at runtime, keep it out of images and source control, and
rotate the Gateway and this service together.

## Remaining CVCL-02 work

This boundary does not yet authenticate the service's outbound calls to
Payment, Document Store, or Application Tracker. Runtime secret wiring and
fleet-level end-to-end negative tests are also required before beta. User
approval, prompt safety, output quality, idempotency, and transaction recovery
remain in their existing workstreams and are not expanded by this change.
