# AGENTS.md

Project rules for Codex in this repository. Machine-specific instructions live in `CLAUDE.local.md` (git-ignored).

## Project

Back-end of João Abreu's portfolio (https://joaoabreu.dev), used by the static Astro site in `jg-abreu/portfolio`. It serves the private admin (GitHub login, projects, recommendations, profile card, contact inbox), the public data read at build time, the visit counter, the contact form, the profile-card SVG and the AI assistant. Live since the V2 cutover on 2026-11-14.

- **Stack:** Java 21, Spring Boot 4.1 (Web MVC, Security with OAuth2 client, Data JPA, Validation, Actuator, Mail), PostgreSQL 18 with Flyway, Maven wrapper.
- **External services:** GitHub (OAuth, profile data, workflow dispatch), Google Gemini (assistant), Google Books (recommendations), SMTP (contact delivery).
- **Hosting:** Lightsail VM `portfolio-v2-2gb`; `compose.production.yaml` runs two API instances (A on `127.0.0.1:8080`, B on `127.0.0.1:8081`) and PostgreSQL; nginx balances `/api` by the visitor's `CF-Connecting-IP`. See `PRODUCTION.md` and the site's `deploy/README.md`.

## Division of responsibilities

| Area | Who does the work | Codex's role |
|---|---|---|
| Back-end (code, migrations, tests, knowledge base and System Prompt, docs) | Coding assistant | Implements and tests in small parts; João reviews the code and the flow |
| Deploy and infrastructure (VM, SSH, Docker on the VM, nginx, DNS/Cloudflare, HTTPS, OAuth apps, the VM's `.env` and `smtp.env`, backups) | João, by hand | **Guides only**: explains the concept, gives the next step, reviews and helps debug. Never runs commands on the VM nor handles real secrets |
| Git and GitHub (branches, commits, messages, push, PR description and opening) | Codex | Creates the branch, commits (including files João wrote), pushes and opens the PR |
| Review and merge | João | Codex **never merges or approves a PR** |

When a decision is needed, ask **one question at a time**. Use concepts and tools João already knows; ask before introducing unfamiliar ones. Explain the implementation during review, outside the code, and keep new code free of explanatory comments.

## Project structure

```
src/main/java/dev/joaoabreu/portfolioapi/
  auth/             GitHub OAuth2 login for the single admin (numeric GitHub ID), session cookie, CSRF, session endpoint
  projects/         bilingual project drafts and their separately selected public copies
  recommendations/  recommendation sections, drafts, public copies, normalized images, Google Books search
  publication/      durable build-request queue and the worker that dispatches the site's deploy.yml on main
  contact/          contact submission (idempotent), private inbox, SMTP outbox worker, 90-day retention
  visits/           approximate visit counter with HMAC identifiers and trusted client-address resolution
  profile/          cached GitHub profile-card SVG and its admin editor (draft and publish)
  assistant/        AI assistant: public v1 endpoint, request filter (size, media type, rate limits),
                    knowledge resources, Gemini adapter, problem responses
  network/          ClientAddressKey: visitor address through the trusted nginx → Cloudflare chain
  instance/         instance name (A/B) on /api/instance and in a response header
src/main/resources/
  application.properties        shared settings; optional features are off by default
  application-local.properties  local profile (OAuth values come from the environment, never from Git)
  db/migration/V<n>__*.sql      Flyway migrations; never edit one that has already run, add the next version
  assistant/knowledge-base.json authorized facts and public sources the assistant may use
  assistant/system-prompt.md    System Prompt (English; answers follow the request locale)
src/test/java/...               JUnit tests; PostgreSQL through Testcontainers, external providers always simulated
compose.yaml                    local PostgreSQL on port 15432, started by spring-boot-docker-compose
compose.production.yaml         production: two API instances + PostgreSQL
.env.production.example         variable names for the VM's private `.env`
deploy/backup/                  daily encrypted backup script and its systemd service/timer
scripts/                        audit-maven-osv.py (dependency audit), two-instance-review.py (local two-instance exercise)
.github/                        Dependabot and CodeQL
PRODUCTION.md                   how the production environment is prepared and run
docs/                           one spec per feature (assistant, publication, recommendations, contact, visits,
                                profile card, backup/restore, dependency security, local two-instance exercise)
```

`docs/assistant.md` is kept identical in this repository and in `portfolio`.

## API rules

- Same-origin only: the site reaches the API through nginx (`/api/`, `/oauth2/`, `/login/oauth2/`, `/admin`) and, in development, through Astro's dev proxy. No CORS policy.
- Routes are denied by default. Admin routes require the GitHub session, and their mutations require CSRF. Open a new public route only with João's decision, and add it to the README's HTTP overview.
- Errors use `application/problem+json` and never echo visitor input, secrets or provider details.
- Optional features (contact, mail, retention, visits, publication, profile card) stay off by default and are enabled per environment.
- Two instances run in production: anything kept in memory (rate limits, caches) is per instance, and shared state belongs in PostgreSQL.
- Real credentials (OAuth, Gemini, GitHub tokens, SMTP, HMAC key, database password) live only in environment variables or the VM's private files (mode 600). Never commit them, print them or paste them into chat.
- Tests never call external providers for real; real calls happen only when João asks for them.
- Knowledge files and the System Prompt stay in English and contain only information João authorized.

## Git workflow

- Everything merged into `main` can be deployed, so `main` must always be deployable. **No direct commits to `main`.**
- One branch per stage, prefixed: `chore/`, `ci/`, `feat/`, `fix/`, `docs/`, `perf/`, `test/`. Before starting a stage, `git pull` on `main` and branch from it. `feat/v2` is historical and receives no new work.
- A change that spans both repositories gets one branch and PR in each.
- Small, frequent commits in English using Conventional Commits (`feat:`, `fix:`, `chore:`, `ci:`, `docs:`, `perf:`, `refactor:`, `test:`).
- Branch, file and folder names in English.
- PR description in English: what changed, why, how to test, and the definition-of-done checklist.
- Work happens on two machines: `git pull` at the start of a session; commit and push everything at the end.

## Keeping CLAUDE.md and AGENTS.md in sync

`CLAUDE.md` and `AGENTS.md` hold the same project rules, one file per coding assistant. They must stay identical except for the title line and the assistant's name.

- Any change to the project rules goes into **both files in the same commit**.
- Machine-specific instructions (vault path, handoff steps) do not go here; they live in the git-ignored `CLAUDE.local.md`.
- Before committing, check that only the title line differs (both sides are normalized to the same assistant name):

  ```bash
  n='s/Claude Code/Codex/g;s/Claude/Codex/g'; diff <(sed "$n" CLAUDE.md) <(sed "$n" AGENTS.md)
  ```

## Definition of done (per stage)

- [ ] `./mvnw test` passes with zero failures, errors or skipped tests
- [ ] Schema changes come as a new Flyway migration, applied by the tests
- [ ] Authentication and CSRF are unchanged for admin routes, with tests covering any new route
- [ ] Behavior holds with two instances (no correctness depending on one process's memory)
- [ ] No secret in the code, tests, logs or commit
- [ ] The feature's spec in `docs/` (and `docs/assistant.md` in both repositories, when it changes) reflects the change
- [ ] João has reviewed the code and the flow

## Commands

```bash
./mvnw test                                                   # full suite (needs Docker for Testcontainers)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local       # API on http://localhost:8080 (starts compose.yaml)
./mvnw -DskipTests package                                    # executable JAR in target/
```

On Windows PowerShell use `.\mvnw.cmd`. Docker must be running: it hosts the local PostgreSQL (port 15432) and the test databases.

## Session context

Use only Google Drive for Second Brain notes; the retired Obsidian app and MCP are not session prerequisites. Read the original `CLAUDE.local.md` for the local Drive path, then the transition, universal rules and project Status. Update Status and Log at an authorized session close, and the transition when passing the baton. Keep historical entries intact.
