# Automatic publication preparation

Selecting or withdrawing a public project/recommendation copy now records a durable build request in the API's V8 singleton queue, in the same database transaction as the selected-copy change. Draft creation, editing and archiving do not request builds. Rollback leaves neither a new selected copy nor its request committed.

The optional dispatcher is disabled by default. The admin's EN/PT message reports disabled, pending, dispatching or dispatch accepted; it never interprets acceptance as a successful build/deployment. Existing per-item revision manifests remain the evidence of the static build being served. The status is private, cleared at logout and guarded against replies arriving after the workspace is hidden. Older review APIs without the status route show an unavailable message without preventing draft editing.

## Implemented API behavior

`GET /api/admin/publication-status` requires the authorized admin session and returns `Cache-Control: no-store`. It exposes queue metadata only, with no token, visitor text or draft payload. `requestedGeneration` tracks committed changes; `dispatchedGeneration` tracks accepted requests, not deployed versions. An enabled empty queue reports IDLE. Disabled mode preserves pending requests without sending anything.

With `PORTFOLIO_PUBLICATION_ENABLED=true` and a private `PUBLICATION_GITHUB_TOKEN`, Spring polls every 30 seconds. A two-minute database lease allows only one worker to claim a generation. Changes arriving during a dispatch remain queued for a later poll. Network calls run outside the publication transaction. Success acknowledges only the claimed generation and lease; failure retries after five minutes. Expired leases can be reclaimed, and a stale worker cannot release or acknowledge the new lease.

Dispatch is at least once: an interrupted connection or process crash after GitHub acceptance may create duplicate runs. The receiving workflow must serialize updates and safely rebuild from the latest selected source. A 200/204 response means GitHub accepted a dispatch; later workflow failure is not automatically retried by this queue. Its static manifests will still identify items as pending. Operators must review the Actions failure and rerun after fixing its cause.

## Receiving workflow and enabling

The worker dispatches the site's existing `deploy.yml` on `main` (`POST /repos/jg-abreu/portfolio/actions/workflows/deploy.yml/dispatches`, ref `main`). That workflow already accepts `workflow_dispatch`, builds from the published API data, fails before replacing the served release when a source is unavailable, and serializes runs with `concurrency: deploy`. Enable it with `PUBLICATION_ENABLED=true` and a fine-grained `PUBLICATION_GITHUB_TOKEN` limited to `jg-abreu/portfolio` with Actions read and write; do not reuse the profile-card token or the OAuth secret.

GitHub accepts manual events only for a workflow present on the default branch. Since the V2 cutover on 2026-11-14, `deploy.yml` with `workflow_dispatch` is on `main`, so the receiving side exists; the dispatch writes the live site. A missing or renamed workflow returns an error and leaves the request queued.

João's checklist for enabling the dispatcher on the production VM (infrastructure work, done by hand):

- Create the fine-grained token privately, limited to `jg-abreu/portfolio` with Actions read and write, and note its expiration. Do not reuse the profile-card token or the OAuth secret.
- Inspect queued generations before enabling the worker: earlier selections may already have recorded requests. Never erase a real pending request to simplify a test.
- Set `PUBLICATION_ENABLED=true` and the token in the VM's `.env`, then recreate both API containers.
- Verify select → dispatch → successful deploy run → matching served manifest (`/project-publications.json` or `/recommendation-publications.json`), then withdrawal → absence. Confirm that a failed build leaves the served release intact.
- Restore/save only validated public GitHub/blog caches across CI runs; never cache private environments, admin responses or credentials.

References: [workflow dispatch REST API](https://docs.github.com/en/rest/actions/workflows), [events that trigger workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows).

## Local validation — 2026-11-13

The complete API suite passed 131 tests, zero failures, errors or skips. New coverage includes V8 on disposable PostgreSQL, transactional rollback, committed change coalescing, overlapping worker claims, expired/stale leases, retry delay and recovery, changes during dispatch, private status access, disabled-by-default wiring and autonomous Spring scheduling with a mocked GitHub transport. Existing project/recommendation tests confirm that selecting/withdrawing requests builds while editing/archiving and rejected stale/incomplete selections do not.

The fixed GitHub target/ref, private authorization header and 200/204 acceptance are tested without real credentials or remote runs. This is not a live GitHub Actions or deploy test. The habitual review API JAR and database were not restarted or migrated; V8 was applied only to disposable test databases in this stage.
