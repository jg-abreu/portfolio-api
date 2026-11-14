# Production preparation

Run these commands on the new VM, in `/opt/portfolio-api`. Use `compose.production.yaml` explicitly; `compose.yaml` remains local development only.

Create `.env` with mode `600`, a generated PostgreSQL password, and the variables listed in `.env.production.example`. Keep real credentials on the VM. Do not commit `.env` or paste its contents into chat. Use a separate GitHub OAuth application for the production callback. The `v2.joaoabreu.dev` URLs are the intended preview target; DNS, TLS and nginx routing must be configured before testing login.

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
