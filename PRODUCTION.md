# Production preparation

Run these commands on the new VM, in `/opt/portfolio-api`. Use `compose.production.yaml` explicitly; `compose.yaml` remains local development only.

Create `.env` with mode `600`, a generated PostgreSQL password, and the variables listed in `.env.production.example`. Keep real credentials on the VM. Do not commit `.env` or paste its contents into chat. Use a separate GitHub OAuth application for the production callback. Since the cutover on 2026-11-14 this VM serves `joaoabreu.dev` (and the `v2.joaoabreu.dev` preview); the OAuth application lists both callbacks.

Validate without printing resolved secrets:

```sh
sudo docker compose -f compose.production.yaml config --quiet
sudo docker compose -f compose.production.yaml --profile api config --quiet
```

Prepare the database independently:

```sh
sudo docker compose -f compose.production.yaml up -d postgres
sudo docker compose -f compose.production.yaml ps
```

The database has no published host port. Its data persists in the named volume; a volume is not an external backup. Changing `POSTGRES_PASSWORD` after first initialization does not change the existing database role password. Preserve the original `.env`.

The API uses the official Java 21 runtime image and an executable JAR built and tested outside the VM. Transfer it as `portfolio-api.jar` with mode `644` so the non-root container user can read it. The missing JAR fails the bind mount instead of creating a directory. The API profile is explicit; starting only the database does not start the application.

After the JAR, OAuth variables, external backups and nginx HTTPS proxy are ready:

```sh
sudo docker compose -f compose.production.yaml --profile api up -d
curl --fail http://127.0.0.1:8080/actuator/health
```

The proxy must overwrite forwarded headers and route `/api/`, `/oauth2/`, `/login/oauth2/` and the backend admin entry appropriately. The API port binds only to `127.0.0.1`; the cookie remains HTTPS-only. Keep the old production VM and static IP until the new environment is validated.

Memory limits are initial budgets for a 2 GB VM: PostgreSQL 384 MiB and API 768 MiB, leaving room for the operating system, nginx and Docker. Measure under real workload before calling these limits sufficient. Logs rotate at three 10 MB files per service. The image tags receive updates; pull and validate updates deliberately.

This configuration does not deploy the application, configure DNS or trigger static site rebuilds. Automatic publication and backup restore verification remain pending.

## Operational notes

These notes were previously in the README.

- Optional features are enabled per environment, so a feature documented in the README is not necessarily switched on in production. The frontend's contact/visit flags are separate from the API flags.
- For an isolated review database, configure `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`, and disable Compose lifecycle integration with `SPRING_DOCKER_COMPOSE_ENABLED=false`. Never point integration tests or synthetic SMTP experiments at production.
- Do not enable SMTP on a review database without inspecting its pending queue. Contact acceptance means the message was persisted, not that an email has arrived. SMTP delivery is at least once and may rarely produce a duplicate after an interrupted attempt.
- Rate limits for contact and assistant are process-local, not distributed spending caps. Behind Cloudflare and nginx they key on the visitor's `CF-Connecting-IP` only through the trusted proxy chain (`network/ClientAddressKey`). `GET /api/instance` names the instance that served the request, and `/api/admin/profile-card` is the admin card editor.
- A passing test suite does not certify live provider credentials, production routing or model factual accuracy. Separate local checks confirmed real SMTP delivery and real Google Books search; their scope is recorded in the module documentation. See [Java dependency monitoring](docs/dependency-security.md) for the Dependabot configuration.

## Remaining release work

Production needs persistent PostgreSQL and recommendation image storage, private environment configuration, trusted proxy validation, external backups and a proven restore procedure. A database/container volume alone is not a backup. Builds and tests should run outside the small serving VM. See [backup/restore](docs/backup-restore.md) for the isolated rehearsal and manual acceptance checklist.

Still pending: receiving-workflow configuration and live automatic-publication validation, dedicated card deployment credential/visual approval/profile integration (one real public provider check passed), broader assistant/browser acceptance and deployment quota review (six live provider/proxy requests passed), production resource review beyond the completed local two-instance concurrency smoke, ongoing security monitoring and release-trigger review, external backup/restore validation and authorized joint V2 release. No load test should target the live site. Infrastructure changes and the release cutover follow João's manual workflow; this document does not perform or authorize deployment.
