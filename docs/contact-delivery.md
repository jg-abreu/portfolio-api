# Contact intake and delivery

All three features are disabled by default. No server settings, real SMTP credentials or production data are modified by this implementation.

| Flag | Purpose |
| --- | --- |
| `PORTFOLIO_CONTACT_SUBMISSION_ENABLED=true` | Exposes POST /api/contact and its request filter. |
| `PORTFOLIO_CONTACT_MAIL_ENABLED=true` | Configures SMTP transport and the delivery scheduler. |
| `PORTFOLIO_CONTACT_RETENTION_ENABLED=true` | Enables independent 90-day cleanup, even when SMTP delivery is disabled. |

Before enabling public intake, configure retention, trusted proxy handling and private SMTP settings; validate real delivery. The frontend flag PUBLIC_CONTACT_ENABLED controls only the static form and is not a server security boundary. No CORS permissions or CSRF exceptions were added for contact.

## Receipt contract

POST `/api/contact` requires a same-origin session CSRF token in X-CSRF-TOKEN, JSON, and an Idempotency-Key UUID. Body fields: name (trimmed, 1-100), email (trimmed valid email, max 254, no CR/LF), message (trimmed, 10-5000), website (empty or omitted). The existing database constraints remain in force.

The response is 202 with `{ "status": "accepted" }` after persistence. It does not claim email delivery. Repeating the same UUID and normalized text is acknowledged without inserting twice; a different payload with that UUID returns 409. Migration V6 adds the unique submission key without changing prior migrations. Errors do not echo visitor fields.

The filter rejects bodies above 16 KiB, unsupported content types, non-UTF-8 charsets and compressed content. A bounded read also applies when length is absent. The in-memory limiter admits five requests per servlet address and 100 globally per ten-minute window; rejected input and failed CSRF checks can consume quota. A 429 includes Retry-After. Restart resets counters; they are not distributed.

The limiter uses the servlet client address and does not parse forwarded headers. The deployment must resolve trustworthy visitor addresses before enabling intake: a proxy address shared by every visitor would incorrectly share the per-client quota, while blindly trusting client-supplied headers would allow spoofing. No infrastructure changes were made here.

## Outbox

Every minute, an enabled worker processes up to ten due messages. Each claim is one atomic PostgreSQL UPDATE with FOR UPDATE SKIP LOCKED and a five-minute lease. The transport is invoked outside a long database transaction. Sent state is recorded only after transport success and only if the claim still matches the attempt number.

SMTP/validation failure returns a message to pending with backoff of one, two, four and eight minutes; the fifth failed attempt ends in failed. Expired leases can be reclaimed; exhausted leases are marked failed instead of retried indefinitely. Email text and provider exception details are not logged by the worker.

Delivery is at least once. A crash after SMTP acceptance but before the database update, an expired lease, or partial SMTP recipient delivery can cause a duplicate notification. HTTP retry deduplication does not remove this SMTP limitation. Both destinations are configured server-side; the visitor cannot choose a recipient.

## Retention

João selected 90 days. When independently enabled, the hourly cleanup removes rows older than 90 days, including archived and failed messages; active delivery leases are preserved until expiry. A message may remain until the next hourly sweep. This deletion also removes its deduplication key. The job is disabled in normal development unless explicitly configured; tests use isolated synthetic records.

## Validation

2026-11-13: 84 backend tests passed, including persistence before receipt, retry deduplication, input/CSRF/body/rate protection, SMTP success/failure, five attempts, expired versus active leases and retention. SMTP was simulated; no real email was sent. Integrated local browser and real SMTP worker checks were completed later on the same date; see the section below for scope and remaining release checks.

## Integrated local verification — 2026-11-13

An isolated database portfolio_e2e_20261113_0110 was created without modifying the habitual development database. The real PT browser form persisted exactly one synthetic row and reset only after acknowledgement. The real GitHub admin session displayed it in the EN/PT inbox; read, archive and restore actions updated only inbox state (version 3), leaving delivery pending with zero attempts.

The real ContactDeliveryWorker was explicitly invoked once against that isolated queue using private local SMTP settings. It marked the row sent with one attempt and a sent timestamp. The dev inbox was checked through Gmail and personal receipt was confirmed by João. The autonomous scheduler was not enabled, so this does not validate its timing.

Logout returned the browser to login even after reload, and a separate anonymous inbox request returned 401. All runtime intake, delivery and retention flags were turned off afterward, as was the frontend form flag. The synthetic database was preserved for review. No server, main branch, repository credentials or production data were changed. Phone layout, browser failure cases and scheduler timing remain release checks.
