# Portfolio AI assistant - V2 scope

## Release boundary

The AI assistant is exclusive to V2. Its integration target is `feat/v2` in both repositories: `portfolio` and `portfolio-api`. Feature branches must be based on V2 and their pull requests must target `feat/v2` exclusively.

- Keep all assistant-related code, configuration, knowledge resources, prompts, tests and documentation within V2 and its feature branches; never target `main`.
- Preserve V1 and the currently published website. Do not add or backport the assistant to V1, `main`, or the current production site.
- Do not merge into `main`, push any branch, or deploy without an explicit request from the user for the corresponding action.
- Authorization to implement or test locally does not authorize publication. Existing general Git workflow instructions do not override this feature-specific restriction.
- Before further edits or Git operations, verify the active branch in both repositories. Do not move unrelated local changes or switch to `main` to continue this feature.
- Local development servers, builds and generated artifacts are for V2 verification only; they do not establish that the feature is published.

## Responsibilities and current implementation

| Repository | Assistant scope |
| --- | --- |
| `portfolio` | Bilingual Astro interface, v1 contract types and validation, browser error handling, tests and local development proxy. |
| `portfolio-api` | Gemini configuration, authorized knowledge base and System Prompt, resource validation, model adapter, response assembly, public v1 endpoint, request limits and simulated tests. |

The public contract remains `POST /api/assistant/messages` v1: independent questions, `pt`/`en` locales, and `answered`/`insufficient_knowledge` responses. The backend owns public source metadata, `knowledgeVersion`, and the exact insufficient-knowledge wording.

The HTTP controller is implemented on `feat/v2`. The local Astro proxy is not a production nginx change. The interface and backend service must not be described as a feature already published on the current site.

Knowledge files and the System Prompt are in English; visitor answers remain Portuguese or English according to `locale`. Tests use simulated Gemini responses without real API calls.

## HTTP boundary and request limits

Only `POST /api/assistant/messages` is public and exempt from CSRF. Administrative authentication and CSRF remain enabled, including project mutations and logout. No CORS policy or additional route is opened.

Send an uncompressed UTF-8 `application/json` body containing only `question` and `locale`. The question is trimmed and must contain 1-2,000 Unicode code points; the locale must be exactly `pt` or `en`. Duplicate JSON keys, trailing JSON values, unknown fields and incorrect field types are rejected. Errors use `application/problem+json` with the v1 error codes; visitor input and provider details are not echoed. Optional `fieldErrors` use `REQUIRED`, `INVALID_TYPE`, `INVALID_LENGTH` and `UNSUPPORTED_LOCALE`. Responses use `Cache-Control: no-store`.

The fixed maximum body size is 16 KiB (16,384 bytes), including JSON syntax and whitespace. A bounded read enforces the limit even when `Content-Length` is missing or incorrect. Oversized bodies return `413 REQUEST_TOO_LARGE`; unsupported media types, charsets or content encodings return `415 UNSUPPORTED_MEDIA_TYPE`.

Rate limits use fixed time windows, shared by all requests handled by one application process:

| Property | Default | Meaning |
| --- | --- | --- |
| `portfolio.assistant.rate-limit.requests-per-window` | `10` | Requests per servlet client address per window. |
| `portfolio.assistant.rate-limit.global-requests-per-window` | `100` | Total requests per application instance per window. |
| `portfolio.assistant.rate-limit.window` | `60s` | Window duration, from 1 second to 1 day. |
| `portfolio.assistant.rate-limit.max-clients` | `4096` | Maximum client entries retained during a window. |

These values can be set through Spring configuration, including environment variables such as `PORTFOLIO_ASSISTANT_RATELIMIT_REQUESTSPERWINDOW`. Quota exhaustion or a full client registry returns `429 RATE_LIMITED` and a positive integer `Retry-After` in seconds until the next window. Requests admitted by the limiter consume quota even when later JSON validation fails. Requests rejected for a declared oversized body or unsupported content type do not consume quota.

Counters are atomic, bounded, in-memory and reset on restart; this is not a distributed quota or a monthly provider spending cap. The limiter uses `getRemoteAddr()` and does not parse `X-Forwarded-For` or `Forwarded`. Before a future V2 deployment, configure trusted proxies so the servlet address represents the intended client. Otherwise users behind the same proxy share a quota; untrusted forwarded-header processing must not allow spoofing. No nginx changes or production publication are included in this implementation.

## Publication gate

Any future release work must respect the V2-only boundary. A request for one action (for example, pushing `feat/v2`) does not grant permission for another (merging into `main` or deploying). Confirm the requested release target and scope before carrying out an explicitly requested publication action.

## Validation status (2026-11-12)

### Automated tests with simulated AI

- The complete backend suite passed: 69 tests, zero failures, errors or skipped tests.
- PostgreSQL 18 ran in isolated Testcontainers databases, and all four Flyway migrations were applied. The persistent development database was not used as the test database.
- Gemini was simulated; the real Gemini key was removed only from the test process. OAuth values were fictitious and confined to tests.
- Coverage includes the v1 endpoint, validation, problem responses, body limits, rate limits and concurrency, administrative authentication/CSRF, project drafts and publications.
- The frontend assistant client suite passed all eight tests, including contract error handling, unsafe source URLs, Unicode question length and timeout classification while reading a response body. Before PR preparation, all 23 frontend tests passed on the current V2 base, including the existing publications integration fixture; that fixture simulates the publications API, not Gemini.
- Astro type checking completed with zero errors, warnings or hints. The updated static build completed; existing Shiki/CSP build warnings remain separate from the type-check result.

### Browser checks with a simulated API

A temporary server at `http://127.0.0.1:4322` served the V2 static build and intercepted assistant requests only in that test environment. It did not call Gemini, change the normal Astro application on port 4321, or modify backend authentication/CSRF.

Verified in Chrome:

- PT and EN interface strings and layout in a compact browser window.
- Opening the panel, typing a question, keyboard submission, Escape to close, and Enter to reopen.
- Loading feedback and disabled submission during a delayed simulated response.
- A simulated answer with source links, sources without URLs and an unsafe source URL rendered as non-clickable text.
- The exact approved English insufficient-knowledge answer, with no sources.

Browser testing found that a new answer could remain below the visible area in a compact window. Frontend corrections now focus and scroll to results/errors, prevent question changes during loading, align the input with the 2,000 Unicode-code-point contract and classify response-body timeouts correctly. Automated checks passed after these corrections, but the browser checks were not repeated against the corrected build.

The browser tool stopped when preparing responsive-device emulation because it could not determine the Chrome URL with sufficient confidence. A dedicated phone viewport, visual error states and post-fix focus/scroll behavior remain unverified. This is partial browser validation, not completed mobile or accessibility acceptance testing.

### Real integration status

An earlier minimal direct Gemini API smoke test returned HTTP 200 for a generic prompt. It did not exercise Spring Boot, the authorized knowledge base, source validation or the Astro proxy.

Local OAuth variables have been configured outside the repository. The API was started with the local profile and process-only OAuth/database settings; its health endpoint returned HTTP 200 with UP and an anonymous admin-session request returned HTTP 401.

The user subsequently reported successful manual checks of a technologies answer in Portuguese, an absent-salary-information answer in English and use in a narrow window. These are user-reported runtime checks, separate from automated simulations. No captured response/source audit or completed GitHub browser-login verification was produced by the agent. Do not treat mocked tests as proof of live Gemini integration or general factual accuracy.

### Remaining work

1. Verify the complete GitHub OAuth browser login; local configuration and API startup are confirmed, without weakening authentication or CSRF.
2. When explicitly authorized, capture and audit live v1 response metadata, source selection and exact insufficient-knowledge wording; the user's manual PT/EN checks are recorded above.
3. Repeat browser checks against the corrected build, including a dedicated phone viewport, keyboard focus/scroll, closing by button and the visual error states (including rate-limit recovery).
4. Review actual PT/EN model answers for factual fidelity and relevant source selection.
5. Before any future authorized V2 publication, verify trusted proxy/client-address handling and the intended release target. V1 and the current published site remain outside this feature's scope.

The temporary simulation server was stopped and port 4322 was confirmed without a listener. Its fixtures are not part of normal application behavior. Astro on port 4321 and development PostgreSQL on port 15432 were preserved. The API was subsequently started locally on port 8080. Publication has not been performed.

## Mobile regression review — 2026-11-13

The current V2 static build was reviewed in Chrome responsive emulation at 360 × 653 CSS pixels, in the dark theme. An isolated Python HTTP server at `127.0.0.1:4322` served `dist` and answered only synthetic assistant requests. It did not proxy to Spring Boot, call Gemini, use an AI key, send mail or modify the normal API on 8080 or Astro on 4321.

Verified in Portuguese:
- The dialog, textarea, submit control and contact link stayed inside the phone viewport.
- Submitting a long synthetic answer brought the response and its sources into view; the previous compact-window scroll issue did not reproduce.
- A safe relative source was linked. A source title containing HTML appeared as literal text and its JavaScript URL was not linked or executed.
- Escape closed the dialog and Enter reopened it; the question field remained usable.
- A synthetic 503 showed the localized unavailable message in view, preserving the question and allowing another attempt.
- A synthetic 429 with Retry-After 3 showed localized recovery guidance and disabled submission. After the interval, the button enabled automatically and another request succeeded.
- An insufficient-knowledge display fixture showed no sources. Its text was synthetic UI content, not an audit of the backend's exact approved fallback wording.

Verified in English: the dialog and controls fit the same phone viewport, and a synthetic insufficient-knowledge response was displayed with the knowledge version, no sources and the contact link in view. Portuguese long-answer, failure and cooldown cases were not repeated in English during this review. These are emulated-browser checks, not physical-device or full accessibility certification. No new functional change was needed and no unrelated test suite was rerun for this documentation-only stage.

The simulator was stopped after review. Expected 503 and 429 entries in DevTools came from deliberate fixtures. Public recommendations remained empty through the normal Astro/API route. Existing real GitHub OAuth checks were completed during the recommendation/inbox workflow; the earlier OAuth-pending entry above is historical.

Still pending: real PT/EN model-answer/source fidelity audit, exact live fallback verification, light-theme and broader keyboard/accessibility review, trusted production proxy validation and authorized V2 release preparation. No main merge, production deployment or real Gemini query occurred in this stage.

## Live provider and HTTP audit — 2026-11-13

João configured the existing Gemini key in the Windows environment. It was read privately into child process environments, not printed, committed or placed in browser variables. AI Studio showed the existing development project/key on the Free tier; no key or billing setting was created or changed by this audit.

A one-shot harness used the actual AssistantResources, AssistantService and GeminiAssistantModel with gemini-3.1-flash-lite and knowledge version 2026-11-12.1. Four real requests passed:

| Case | Result | Source/fallback review |
| --- | --- | --- |
| Technologies, PT | answered | technical-knowledge; approved languages, frameworks, database/tools and practices only |
| Technologies, EN | answered | same authorized source/facts; no invented proficiency levels |
| Salary expectation, PT | insufficient_knowledge | exact approved Portuguese fallback; empty sources |
| Salary expectation, EN | insufficient_knowledge | exact approved English fallback; empty sources |

Answers were read and compared with the approved facts, including React fundamentals rather than an invented proficiency. The first call took about 21.6 seconds, others about 1.2–2.2 seconds: individual observations, not a benchmark/SLA.

A separate Spring process used current compiled classes on loopback 8081, a new isolated database and Astro proxy 4324. Two real HTTP requests passed: technologies PT with the approved source and unknown salary EN with exact fallback/no sources. Both returned 200 and Cache-Control: no-store with expected locale/version. Source IDs/titles/URLs matched the knowledge base. Empty input returned 400 and anonymous admin access 401. This covers proxy → HTTP controller/security → service → real provider, not a repeated browser UI audit. Total live calls: six, without conversation history or contact data.

No live rate limit, outage or timeout was induced; simulated tests remain their evidence. This sample does not guarantee general model factual accuracy. Broader UI approval, deployment quotas/proxy settings and authorized release remain pending.

The isolated API and temporary Astro were stopped, synthetic database retained. The habitual API was deliberately repackaged/restarted with the key, 30-minute session and existing isolated review database. Its JAR contains Guava 33.7.2-android. Contact intake, SMTP, retention, visits, dispatch and profile refresh stay disabled. Health was UP, anonymous admin 401 and habitual site 200. No VM/main/deploy/email change occurred. Raw reports remain in ignored staging and contain authorized profile questions/answers, not credentials.
