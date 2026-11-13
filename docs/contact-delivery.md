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

## Scheduler and mobile follow-up — 2026-11-13

ContactSchedulerTests passed with a disposable PostgreSQL Testcontainer and mocked JavaMailSender. It enables the real mail configuration and worker, shortens polling to 100 ms only in test properties and waits for automatic delivery without calling deliver(). The real notifier's message uses the configured sender/recipients and visitor Reply-To; the mock observes sending before the row becomes sent with one attempt and a timestamp. The Spring context is closed after the test. No SMTP credentials, network transport or normal polling defaults are involved. This targeted test was run separately from the earlier 84-test suite.

The browser follow-up checked the contact form and inbox at 360 CSS pixels in EN/PT and light/dark appearances. Disabling intake produced localized error feedback while retaining all fields in both languages. Enabling only local intake allowed the EN mobile form to persist a second synthetic row and clear after acknowledgement. Row 2 remains pending with zero attempts in portfolio_e2e_20261113_0110; row 1 remains sent with one attempt. Delivery and retention were never enabled in this follow-up. Inspect this synthetic queue before enabling a worker against it.

Mobile logout returned to login, device emulation was closed, and local intake and frontend flags were restored to false. No additional email, production configuration change or deployment occurred. Session expiry in an open inbox, cross-tab conflicts, a disconnected network and deployment proxy/retention/backup readiness remain separate release checks.

## Preserving HTTP errors — 2026-11-13

A real cross-tab inbox check exposed an internal Tomcat ERROR dispatch being denied by the route security rules. ResponseStatusException(CONFLICT) became HTTP 403, so the browser treated a stale version as lost authorization. SecurityConfiguration now permits DispatcherType.ERROR only; ordinary requests retain the same ADMIN, CSRF and deny-by-default rules.

ContactHttpErrorTests first reproduced expected 409 versus actual 403 using embedded Tomcat, real HTTP/cookies/CSRF and disposable PostgreSQL. After the fix it verifies 409 for stale updates, 404 for missing records, continued authenticated reads, forbidden invalid CSRF/direct /error requests and anonymous private access returning 401. Its authentication fixture exists only in test sources. The complete verify run passed 86 tests and produced the local JAR.

The browser repetition showed correct EN/PT conflict feedback without logout; reopening loaded the new version and allowed a read-state update. A separate real one-minute idle-session check returned to login and removed message data. The normal local timeout was restored to 30 minutes; contact intake, SMTP and retention stayed disabled. No server or production settings changed.

## Test timing follow-up — 2026-11-13

The expanded full suite exposed an intermittent assumption in `marksSentOnlyAfterSmtpSucceeds`: a newly inserted message was treated as immediately due even though PostgreSQL assigns its schedule and the worker compares it to Java time. A temporary 100-repeat probe reproduced 53 failures, each still pending with zero attempts, before any SMTP call. Making only that test fixture explicitly overdue yielded 100 passing repetitions.

The temporary repetition/probe was removed. The original success test now starts with an explicitly overdue row; a separate regression verifies a future pending row is not sent and becomes eligible only after its schedule is moved into the past. Production worker code, polling, leases and SMTP configuration did not change. The final complete suite passed 133 tests. SMTP remained simulated throughout this follow-up.
