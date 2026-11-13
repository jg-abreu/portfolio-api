# Recommendations

Recommendations use PostgreSQL drafts and separately selected public copies. This stage implements the API and image storage; the admin editor and Google Books search are still pending. Gemini is deferred. Nothing in this feature triggers a build or deployment.

## HTTP contract

All private endpoints require the GitHub admin session. Mutations also require the existing same-origin CSRF token. Responses use `Cache-Control: no-store`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET / POST | `/api/admin/recommendations` | List / create drafts |
| GET / PUT | `/api/admin/recommendations/{id}` | Read / update a draft |
| PATCH | `/api/admin/recommendations/{id}/status` | Archive or restore |
| PUT / DELETE | `/api/admin/recommendations/{id}/publication` | Select / withdraw the public copy |
| GET | `/api/admin/recommendation-publications` | Selected revisions |
| POST | `/api/admin/recommendation-images` | Multipart `image` upload |
| GET | `/api/admin/recommendation-images/{key}` | Private preview |
| GET | `/api/recommendations` | Public schema-version-1 snapshot |
| GET | `/api/recommendation-images/{key}` | Image referenced by a selected public copy |

Create and update requests contain `version` and `data`. Data contains `slug`, `category` (`BOOKS`, `COURSES`, `VIDEOS`, `SETUP`, `TOOLS`), `imageKey`, optional `url` represented as an empty string, nonnegative `displayOrder`, and `en` / `pt` copies with `title`, personal `description` and `imageAlt`. Drafts allow incomplete copies and an empty image key. Nonempty image keys must already exist. Links must be credential-free HTTPS.

Create uses version 0. Editing and status changes increment the version. Publication and withdrawal requests contain the current `version`. Row locks serialize mutations; stale versions return 409 and cannot overwrite another editor. Duplicate draft or selected slugs also return 409. Missing records return 404.

Selecting a copy requires an active draft, complete EN/PT text and an imported image. It captures a UUID revision, the draft version and a separate JSON copy. Later edits or archiving leave that public copy unchanged. Withdrawal removes the selected copy and its public image access when no other selected recommendation uses the image. It preserves the draft and file.

The snapshot matches the static frontend contract and sorts by category, display order and slug. Draft creation is capped at 500 records, including archived drafts. The static frontend downloads selected images during build and does not query this API per visit.

## Image storage

`portfolio.recommendations.media-dir` defaults to `./data/recommendations`, which is Git-ignored. Images are files; only their keys are stored in PostgreSQL. PNG and JPEG inputs are decoded with ImageIO and normalized to PNG without retaining source metadata. Uploads are bounded to 2 MiB, dimensions to 4096 per side and 12 million pixels, and normalized files to 8 MiB. SHA-256 keys identify immutable content and deduplicate uploads. Stored bytes are rechecked against the key before serving. Invalid paths, symbolic image directories and nonregular files are rejected.

Writes use a temporary file and atomic move. Storage is capped at 500 unique PNG files. Unused uploads are retained; there is no automatic deletion, so repeated replacements can eventually require deliberate cleanup. Read failures do not reveal filesystem paths to clients.

Production requires a persistent directory outside the container layer and backups of both the database and image directory. Provisioning, mounts, ownership and restoration validation remain separate infrastructure work. No production files or VM configuration were changed in this stage.

## Validation

`RecommendationImagesTests` covers JPEG normalization, stable content hashes, deduplication, invalid formats, byte and dimension limits, missing/path-like keys and corrupted stored files. `RecommendationTests` exercises HTTP security/CSRF against disposable PostgreSQL, separate public copies, private images, publication completeness, duplicate slugs, version conflicts, withdrawal and simultaneous edits. No personal recommendations or real covers are seeded.
