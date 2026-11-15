# Automatic publication preparation

Selecting or withdrawing a public project/recommendation copy now records a durable build request in the API's V8 singleton queue, in the same database transaction as the selected-copy change. Draft creation, editing and archiving do not request builds. Rollback leaves neither a new selected copy nor its request committed.

The optional dispatcher is disabled by default. The admin's EN/PT message reports disabled, pending, dispatching or dispatch accepted; it never interprets acceptance as a successful build/deployment. Existing per-item revision manifests remain the evidence of the static build being served. The status is private, cleared at logout and guarded against replies arriving after the workspace is hidden. Older review APIs without the status route show an unavailable message without preventing draft editing.

## Implemented API behavior

`GET /api/admin/publication-status` requires the authorized admin session and returns `Cache-Control: no-store`. It exposes queue metadata only, with no token, visitor text or draft payload. `requestedGeneration` tracks committed changes; `dispatchedGeneration` tracks accepted requests, not deployed versions. An enabled empty queue reports IDLE. Disabled mode preserves pending requests without sending anything.

With `PORTFOLIO_PUBLICATION_ENABLED=true` and a private `PUBLICATION_GITHUB_TOKEN`, Spring polls every 30 seconds. A two-minute database lease allows only one worker to claim a generation. Changes arriving during a dispatch remain queued for a later poll. Network calls run outside the publication transaction. Success acknowledges only the claimed generation and lease; failure retries after five minutes. Expired leases can be reclaimed, and a stale worker cannot release or acknowledge the new lease.

Dispatch is at least once: an interrupted connection or process crash after GitHub acceptance may create duplicate runs. The receiving workflow must serialize updates and safely rebuild from the latest selected source. A 200/204 response means GitHub accepted a dispatch; later workflow failure is not automatically retried by this queue. Its static manifests will still identify items as pending. Operators must review the Actions failure and rerun after fixing its cause.

## Receiving workflow: live since 2026-11-15

The worker dispatches the site's `deploy.yml` on `main` (`POST /repos/jg-abreu/portfolio/actions/workflows/deploy.yml/dispatches`, ref `main`). That workflow accepts `workflow_dispatch`, builds from the published API data (`PROJECTS_API_URL` and `RECOMMENDATIONS_API_URL`), fails before replacing the served release when a source is unavailable, and serializes runs with `concurrency: deploy`.

Production enabled it at 00:07 UTC on 2026-11-15: API PR jg-abreu/portfolio-api#20 deployed, `PUBLICATION_ENABLED=true` and a fine-grained `PUBLICATION_GITHUB_TOKEN` limited to `jg-abreu/portfolio` with Actions read and write, created by João and kept only in the VM's private `.env`. The four queued requests were dispatched and run 43657420485 (`workflow_dispatch` on `main`) succeeded, with the site answering 200.

Not yet exercised live: a deliberately failed build keeping the served release, and withdrawal → absence from the served manifest. Keep track of the token's expiration; do not reuse the profile-card token or the OAuth secret.

References: [workflow dispatch REST API](https://docs.github.com/en/rest/actions/workflows), [events that trigger workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows).

## Local validation — 2026-11-13

The complete API suite passed 131 tests, zero failures, errors or skips. New coverage includes V8 on disposable PostgreSQL, transactional rollback, committed change coalescing, overlapping worker claims, expired/stale leases, retry delay and recovery, changes during dispatch, private status access, disabled-by-default wiring and autonomous Spring scheduling with a mocked GitHub transport. Existing project/recommendation tests confirm that selecting/withdrawing requests builds while editing/archiving and rejected stale/incomplete selections do not.

The fixed GitHub target/ref, private authorization header and 200/204 acceptance are tested without real credentials or remote runs. This is not a live GitHub Actions or deploy test. The habitual review API JAR and database were not restarted or migrated; V8 was applied only to disposable test databases in this stage.
