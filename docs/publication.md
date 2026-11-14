# Automatic publication preparation

Selecting or withdrawing a public project/recommendation copy now records a durable build request in the API's V8 singleton queue, in the same database transaction as the selected-copy change. Draft creation, editing and archiving do not request builds. Rollback leaves neither a new selected copy nor its request committed.

The optional dispatcher is disabled by default. The admin's EN/PT message reports disabled, pending, dispatching or dispatch accepted; it never interprets acceptance as a successful build/deployment. Existing per-item revision manifests remain the evidence of the static build being served. The status is private, cleared at logout and guarded against replies arriving after the workspace is hidden. Older review APIs without the status route show an unavailable message without preventing draft editing.

## Implemented API behavior

`GET /api/admin/publication-status` requires the authorized admin session and returns `Cache-Control: no-store`. It exposes queue metadata only, with no token, visitor text or draft payload. `requestedGeneration` tracks committed changes; `dispatchedGeneration` tracks accepted requests, not deployed versions. An enabled empty queue reports IDLE. Disabled mode preserves pending requests without sending anything.

With `PORTFOLIO_PUBLICATION_ENABLED=true` and a private `PUBLICATION_GITHUB_TOKEN`, Spring polls every 30 seconds. A two-minute database lease allows only one worker to claim a generation. Changes arriving during a dispatch remain queued for a later poll. Network calls run outside the publication transaction. Success acknowledges only the claimed generation and lease; failure retries after five minutes. Expired leases can be reclaimed, and a stale worker cannot release or acknowledge the new lease.

Dispatch is at least once: an interrupted connection or process crash after GitHub acceptance may create duplicate runs. The receiving workflow must serialize updates and safely rebuild from the latest selected source. A 200/204 response means GitHub accepted a dispatch; later workflow failure is not automatically retried by this queue. Its static manifests will still identify items as pending. Operators must review the Actions failure and rerun after fixing its cause.

## Infrastructure handoff: not connected or enabled

The implementation sends only to `jg-abreu/portfolio`, workflow `publication-v2.yml`, ref `feat/v2`. This deliberate V2 boundary excludes the existing production `deploy.yml`. The target is fixed in code; the approved final release target needs a reviewed change before ordinary main-based production publication. No workflow, secret, server setting, main commit or deployment was created by this stage.

GitHub requires a workflow with `workflow_dispatch`, and its definition must be present on the default branch to accept manual events. Do not bootstrap that file onto main during V2 development: pushing main also activates the existing production deployment. Therefore a live dispatch test is deferred until the explicit release plan permits registering the receiving workflow. A missing workflow returns an error and leaves the request queued; do not enable the dispatcher to repeatedly test that condition.

João's manual receiving-workflow checklist for the authorized release:

- Confirm the branch, environment, directory and API origin; the initial V2 workflow must not write the production directory or change DNS/IPs.
- Register the dedicated dispatch entry point with the required branch guard and protected environment. Match the API's ref and workflow filename exactly.
- Build selected projects and recommendations using both `PROJECTS_API_URL` and `RECOMMENDATIONS_API_URL`; a configured source failure must fail the build before replacing a served release.
- Run the site checks/tests, then preserve both publication manifests in the generated output. Upload/deploy only a successful complete build.
- Set concurrency to serialize runs; never cancel a run halfway through replacing served files. Review release/rollback behavior explicitly.
- Restore/save only validated public GitHub/blog caches across CI runs, so a fresh checkout can keep a prior verified snapshot during provider outages. Never cache private environments, admin responses or credentials.
- Configure the fine-grained token privately, limited to this repository and Actions write permission, and verify its expiration. Do not reuse the profile-card token or OAuth secret.
- Verify a synthetic select → dispatch → successful static build → matching served manifest, then withdrawal → absence, in the designated V2 environment. Deliberately fail one build and confirm the preceding release stays intact.
- Inspect queued generations before enabling the worker: earlier local selections may already have recorded requests. Use separate databases for synthetic checks, and never erase a real pending request to simplify a test.
- Confirm the final main/release target with João before replacing the V2-only constants. No permission to merge or launch is implied by the existence of this module.

References: [workflow dispatch REST API](https://docs.github.com/en/rest/actions/workflows), [events that trigger workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows).

## Local validation — 2026-11-13

The complete API suite passed 131 tests, zero failures, errors or skips. New coverage includes V8 on disposable PostgreSQL, transactional rollback, committed change coalescing, overlapping worker claims, expired/stale leases, retry delay and recovery, changes during dispatch, private status access, disabled-by-default wiring and autonomous Spring scheduling with a mocked GitHub transport. Existing project/recommendation tests confirm that selecting/withdrawing requests builds while editing/archiving and rejected stale/incomplete selections do not.

The fixed GitHub target/ref, private authorization header and 200/204 acceptance are tested without real credentials or remote runs. This is not a live GitHub Actions or deploy test. The habitual review API JAR and database were not restarted or migrated; V8 was applied only to disposable test databases in this stage.
