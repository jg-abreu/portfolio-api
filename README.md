# Portfolio API

Spring Boot backend for [João Abreu's portfolio](https://joaoabreu.dev), built with Java 21, Spring Security, PostgreSQL and Flyway. The companion [Astro site](https://github.com/jg-abreu/portfolio) remains static; this API provides the private workspace and interactive features.

**V2 is under development on `feat/v2`.** Features documented here are local implementations, not a claim that the published site runs this version. Release, profile README integration and automatic static-site publication remain separate steps.

## Features

| Module | Behavior |
| --- | --- |
| Admin | GitHub OAuth with PKCE, numeric account-ID authorization, cookie sessions and CSRF-protected changes. |
| Projects | Bilingual drafts and independently selected public copies, optimistic version checks and archive/restore. |
| Recommendations | Bilingual drafts, categories, separate public copies, normalized private image uploads and Google Books edition selection. |
| Contact | Persistence before acknowledgment, HTTP retry deduplication, private inbox, SMTP outbox with retries and independent 90-day retention. |
| Visits | Approximate total, one counted visit per canonical IP per 30 minutes, HMAC identifiers and transactional concurrency handling. |
| Assistant | Independent EN/PT questions against an authorized knowledge base, Gemini adapter, validated sources and bounded requests. |
| Profile card | Cached SVG with GitHub contributions and latest public repository, background refresh and last-good fallback. |

Saving a draft does not publish it. Selecting a public copy prepares the next static build; it records a durable request for the optional dispatcher. The receiving workflow is not connected yet; see [publication](docs/publication.md). The site reads selected projects/recommendations at build time and emits revision manifests. Failed configured sources stop the build so the prior deployed release can be retained.

## Local development

Requirements: JDK 21, Docker with Compose, and Git. The Maven wrapper is included. Start Docker before running the application or integration tests.

Configure these variables privately in the process environment or IDE run configuration:

| Variable | Local purpose |
| --- | --- |
| `GITHUB_CLIENT_ID` | Client ID of a separate local GitHub OAuth application. |
| `GITHUB_CLIENT_SECRET` | Its secret; never commit or paste into logs. |
| `GITHUB_REDIRECT_URI` | Callback registered in that OAuth application, for example `http://localhost:4321/login/oauth2/code/github` when using the Astro proxy. |
| `SPRING_PROFILES_ACTIVE` | `local` for local HTTP cookie settings and the Astro admin redirect. |

The permitted admin defaults to João's numeric GitHub ID. If adapting this project, deliberately configure `PORTFOLIO_ADMIN_GITHUB_ID` for your own account rather than relying on a mutable username.

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

macOS/Linux:

```sh
./mvnw spring-boot:run
```

With the `local` profile active, the bundled development Compose file provides PostgreSQL on host port 15432. Spring Boot's Docker Compose integration supplies its connection details. These development database settings are not production credentials. The API defaults to port 8080; the companion Astro dev server runs on 4321 and proxies the API/OAuth routes. Open the admin through Astro to preserve the intended same-origin flow.

For an isolated review database, configure `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`, and disable Compose lifecycle integration with `SPRING_DOCKER_COMPOSE_ENABLED=false`. Never point integration tests or synthetic SMTP experiments at production.

## Optional integrations

Contact intake, mail delivery, retention, visits and the profile card are disabled by default. Enable only the features intentionally configured for the environment.

| Setting | Details |
| --- | --- |
| `PORTFOLIO_CONTACT_SUBMISSION_ENABLED` | Contact receipt endpoint; see [contact delivery](docs/contact-delivery.md). |
| `PORTFOLIO_CONTACT_MAIL_ENABLED` | Scheduled outbox delivery; private SMTP configuration in [SMTP guide](docs/contact-smtp.md). |
| `PORTFOLIO_CONTACT_RETENTION_ENABLED` | Independent 90-day message cleanup. |
| `PORTFOLIO_VISITS_ENABLED` | Requires a private HMAC key and validated client-address mode; see [visits](docs/visits.md). |
| `GOOGLE_BOOKS_API_KEY` | Server-side Books API key; see [recommendations](docs/recommendations.md). |
| `GEMINI_API_KEY` | Private model credential; see [assistant](docs/assistant.md). Missing configuration leaves the assistant unavailable. |
| `PORTFOLIO_PUBLICATION_ENABLED` | Optional V2-only workflow dispatcher; private `PUBLICATION_GITHUB_TOKEN` and manually prepared receiver required. |
| `PORTFOLIO_PROFILE_CARD_ENABLED` | Enables the public SVG route. |
| `PROFILE_CARD_GITHUB_TOKEN` | Minimal public-data GraphQL access for background card refresh; see [profile card](docs/profile-card.md). |

The frontend's contact/visit flags are separate from API flags. Do not enable SMTP on a review database without inspecting its pending queue. Contact acceptance means the message was persisted, not that an email has arrived. SMTP delivery is at least once and may rarely produce a duplicate after an interrupted attempt.

## HTTP overview

| Route | Access |
| --- | --- |
| `GET /actuator/health` | Public health status. |
| `GET /api/projects`, `/api/recommendations` | Selected public copies for static builds. |
| `GET /api/recommendation-images/{key}` | Only images referenced by selected public recommendations. |
| `GET /api/auth/csrf` | Same-session CSRF token for protected browser mutations. |
| `/api/admin/**` | Authorized admin session; mutations also require CSRF. |
| `POST /api/auth/logout` | Invalidates the session; requires CSRF. |
| `POST /api/contact` | Optional receipt endpoint with CSRF and UUID `Idempotency-Key`. |
| `GET /api/visits`, `POST /api/visits` | Optional counter; registration requires CSRF. |
| `POST /api/assistant/messages` | Public, bounded and rate limited; exempt from CSRF. |
| `GET /api/profile-card.svg` | Optional cached image with ETag/304; no visitor-triggered GitHub request. |

Other routes are denied by default. Rate limits for contact and assistant are process-local, not distributed spending caps. Trusted proxy configuration must be reviewed before exposing these endpoints behind Cloudflare/nginx.

## Tests

```powershell
.\mvnw.cmd test
```

The complete suite passed **133 tests** on 2026-11-13, with zero failures, errors or skips. PostgreSQL integration tests use disposable Testcontainers databases; SMTP, Gemini, Books and card-provider responses are simulated in automated tests. A passing suite does not certify live provider credentials, production routing or model factual accuracy.

Tests cover security/session/CSRF, HTTP limits, concurrent counter updates, stale draft versions, separate publication copies, image normalization/integrity, outbox leases/retries, scheduling/retention and SVG cache/fallback behavior. Separate local checks confirmed real SMTP delivery to both configured recipients and real Google Books search; their exact scope is recorded in the module documentation.

See [Java dependency monitoring](docs/dependency-security.md) for the prepared Dependabot configuration and the distinction between version checks and completed security scans.

## Operations and remaining release work

See [PRODUCTION.md](PRODUCTION.md) for the existing deployment guide and [backup/restore](docs/backup-restore.md) for the isolated rehearsal and manual acceptance checklist. Production needs persistent PostgreSQL and recommendation image storage, private environment configuration, trusted proxy validation, external backups and a proven restore procedure. A database/container volume alone is not a backup. Builds and tests should run outside the small serving VM.

Still pending: receiving-workflow configuration and live automatic-publication validation, real card-provider verification and profile integration, real assistant answer/source audit, local two-instance load testing, security scanning setup, backup/restore validation and authorized joint V2 release. No load test should target the live site. Infrastructure changes and the release cutover follow João's manual workflow; this README does not perform or authorize deployment.
