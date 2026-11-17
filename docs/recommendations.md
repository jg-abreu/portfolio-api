# Recommendations

Recommendations use PostgreSQL drafts and separately selected public copies. This stage implements the API and image storage; the frontend admin editor and Google Books search are implemented; real provider validation still needs a local key. Gemini is deferred. Nothing in this feature triggers a build or deployment.

## HTTP contract

All private endpoints require the GitHub admin session. Mutations also require the existing same-origin CSRF token. Responses use `Cache-Control: no-store`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET / POST | `/api/admin/recommendation-sections` | List (with active item counts) / create sections |
| PUT / DELETE | `/api/admin/recommendation-sections/{id}` | Rename or retype / delete an empty section |
| PUT | `/api/admin/recommendation-sections/order` | `{ids}`: every section, in display order |
| PUT | `/api/admin/recommendations/order` | `{sectionId, ids}`: every item of the section, in display order |
| GET / POST | `/api/admin/recommendations` | List / create drafts |
| GET / PUT | `/api/admin/recommendations/{id}` | Read / update a draft |
| PATCH | `/api/admin/recommendations/{id}/status` | Archive or restore |
| PUT / DELETE | `/api/admin/recommendations/{id}/publication` | Select / withdraw the public copy |
| GET | `/api/admin/recommendation-publications` | Selected revisions |
| POST | `/api/admin/recommendation-images` | Multipart `image` upload |
| GET | `/api/admin/recommendation-images/{key}` | Private preview |
| GET | `/api/recommendations` | Public schema-version-2 snapshot |
| GET | `/api/recommendation-images/{key}` | Image referenced by a selected public copy |

Sections (migration V11) are free: each has an EN/PT title and a kind, `BOOKS` or `ITEMS`, at most 20. A section can be retyped or deleted only while it holds no items.

Create and update requests contain `version` and `data`. Data contains `slug` (empty: generated from the title and kept unique), `sectionId`, `imageKey`, optional `url` represented as an empty string, `rating`, `bookGroup`, `subtitle` (author or brand, up to 150 characters), `googleBooksId` and `en` / `pt` copies with `title`, a short personal `description` (up to 300 characters) and `imageAlt` (empty: "Cover of …"/"Capa de …" for books, "Photo of …"/"Foto de …" for items). Books need a `rating` from 1 to 5 and a `bookGroup` (`TECHNICAL` or `NON_TECHNICAL`); items must have rating 0, no group and no Google Books reference. Order is not part of `data`: new items go to the end of their section, and the order endpoints rearrange them. Drafts allow incomplete copies. Nonempty image keys must already exist. Links must be credential-free HTTPS. V11 removed the earlier category-based drafts; production had none.

Create uses version 0. Editing and status changes increment the version. Publication and withdrawal requests contain the current `version`. Row locks serialize mutations; stale versions return 409 and cannot overwrite another editor. Duplicate draft or selected slugs also return 409. Missing records return 404.

Selecting a copy requires an active draft and complete EN/PT title and description; books also need an imported image or a verified Google Books cover reference, while items may have no image. It captures a UUID revision, the draft version and a separate JSON copy. Later edits or archiving leave that public copy unchanged. Withdrawal removes the selected copy and its public image access when no other selected recommendation uses the image. It preserves the draft and file.

The snapshot (`{schemaVersion: 2, sections: [{id, kind, en, pt, items}]}`) lists only sections with selected items, in section order, and each section's items in the current draft order; section titles and order are read live, so renaming or reordering requests a new build when something is published. Draft creation is capped at 500 records, including archived drafts. The static frontend downloads selected images during build and does not query this API per visit.

## Image storage

`portfolio.recommendations.media-dir` defaults to `./data/recommendations`, which is Git-ignored. Images are files; only their keys are stored in PostgreSQL. PNG and JPEG inputs are decoded with ImageIO and normalized to PNG without retaining source metadata. Uploads are bounded to 2 MiB, dimensions to 4096 per side and 12 million pixels, and normalized files to 8 MiB. SHA-256 keys identify immutable content and deduplicate uploads. Stored bytes are rechecked against the key before serving. Invalid paths, symbolic image directories and nonregular files are rejected.

Writes use a temporary file and atomic move. Storage is capped at 500 unique PNG files. Unused uploads are retained; there is no automatic deletion, so repeated replacements can eventually require deliberate cleanup. Read failures do not reveal filesystem paths to clients.

Production requires a persistent directory outside the container layer and backups of both the database and image directory. Provisioning, mounts, ownership and restoration validation remain separate infrastructure work. No production files or VM configuration were changed in this stage.

## Validation

`RecommendationImagesTests` covers JPEG normalization, stable content hashes, deduplication, invalid formats, byte and dimension limits, missing/path-like keys and corrupted stored files. `RecommendationTests` exercises HTTP security/CSRF against disposable PostgreSQL, separate public copies, private images, publication completeness, duplicate slugs, version conflicts, withdrawal and simultaneous edits. No personal recommendations or real covers are seeded.

## Google Books references

`GET /api/admin/books?q=...` returns at most 12 provider results in their original order. `POST /api/admin/books/{volumeId}/selection` verifies a choice and requires CSRF. Both routes require the existing admin session and return no-store responses. Search accepts 2–150 characters, supports title/author/ISBN as plain search terms and runs only when requested. No background indexing or automatic search is performed.

Configure `GOOGLE_BOOKS_API_KEY` (or `portfolio.books.api-key`) only on the backend. An empty key disables provider calls with a generic 503. Restrict the standard key to Books API in the intended Google Cloud project, check its quota, and never store the key in source, chat, notes or screenshots. No billing, paid trial, API enablement or real key creation was performed by this implementation. Local IDE environment variables are an acceptable private development configuration.

The Java HTTP client uses the fixed Google APIs HTTPS endpoint, no redirects, a 3-second connect timeout, 10-second request/response timeout and a 1 MiB bounded body subscriber. A one-second application-wide interval limits repeated calls. Provider 429 is exposed as a retryable rate limit; unavailable/malformed/oversized responses are mapped to a generic 503 without secret-bearing exception details. The key never reaches the frontend.

Draft `data` accepts an optional `googleBooksId`; null/empty means no provider reference and preserves compatibility with older drafts. A reference must match the bounded volume ID format, use category BOOKS and have an empty local `imageKey`. Publication rechecks that the requested edition exists and has a cover, then captures only the reference and the user's own bilingual text. No provider metadata or cover bytes are persisted. Google Books outages prevent selecting a new provider-backed copy but do not affect existing static builds.

The frontend constructs cover and book links from validated IDs and a fixed `books.google.com` origin, displays the official attribution graphic and keeps uploads as a separate option. Referenced covers load directly from Google with browser-controlled caching. This differs from manual images, which remain normalized local files. Google API terms section 5(e) restrict permanent copies and excessive caching; provider content is not imported into the local image store.

References:
- https://developers.google.com/books/docs/v1/reference/volumes/list
- https://developers.google.com/books/docs/v1/reference/volumes/get
- https://developers.google.com/terms
- https://developers.google.com/books/branding

`GoogleBooksTests` covers bounded input/response handling, original order, fixed-origin URLs, configuration absence, throttling, provider errors and edition verification without network calls or real quota use. `GoogleBooksPublicationTests` verifies session/CSRF protection, reference-only publication and mutually exclusive cover sources with a mocked provider and disposable PostgreSQL. Actual search results, cover availability and account quota still require the user's key and a manual review.

## Section pages and external links — 2026-11-17

Migration V12 gives every section an address (`slug`, generated from the English title and kept unique; an explicit duplicate returns 409), an optional short EN/PT `description` (up to 200 characters) and a third kind, `LINK`: a section that points to an external HTTPS `url` (for example an Amazon list) and holds no items. Other kinds must have an empty `url`. Existing sections received a slug from their title.

Books accept ratings from 0.5 to 5 in half steps and an optional `readIn` (`PT` or `EN`); items keep rating 0 and no reading language.

The public snapshot is schema version 3: each section carries `slug`, `kind`, `url`, `en`/`pt` with `title` and `description`, and its items. `LINK` sections are listed even without items; the others only when something is published. Creating, editing, reordering or deleting a `LINK` section requests a new build.
