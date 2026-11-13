# Backup and restoration

## Local rehearsal — 2026-11-13

`BackupRestoreTests` starts two independent disposable PostgreSQL 18 containers. Flyway applies the eight current migrations to the source. Fixtures include a synthetic pending contact and idempotency key, an approximate visit total, bilingual recommendation draft/publication records, an immutable normalized image and queued publication generations. No application scheduler, SMTP sender, Gemini or remote GitHub dispatch runs during this test.

The test writes a custom-format archive using the real `pg_dump`, copies it out of the source container and records its SHA-256. Image files are copied separately into a temporary backup directory. It then changes the source message/counter and corrupts the source image deliberately, proving the restore does not read the current source state. These are disposable synthetic records and files only.

The archive is verified and copied into the second container. `pg_restore --no-owner --exit-on-error` restores into its empty database. Flyway validates the restored history and reports no pending migration. Assertions check the original contact and pending delivery state, counter, queue generations, exact bilingual publication data/revision/image reference, foreign-key enforcement and the next generated contact ID. The restored image is read through the real `RecommendationImages` integrity check and decoded at its original dimensions. Source changes after backup do not appear in the restored copy.

This is a local custom-dump/restore and separate-image-copy rehearsal, not a backup service, remote disaster-recovery test or proof that the VM has external backups. It does not export real contact data, create storage accounts, transfer anything to Drive, set retention policy for backups or modify infrastructure. JUnit and Testcontainers own cleanup of their temporary files and containers.

The focused restoration test passed. The final complete API suite then passed 133 tests with zero failures, errors or skips. No real message, provider quota, external upload, habitual database or VM was involved.

## Manual production acceptance checklist

Infrastructure remains João's manual work. Before enabling contact intake or launching V2:

1. Confirm the intended V2 host, database, PostgreSQL version and actual persistent image directory. A container layer or VM-local volume alone is not an external backup.
2. Decide a private external destination, recovery-point/recovery-time targets, backup frequency, retention and access policy. Database archives contain contact names, emails and messages: encrypt and restrict them; do not put them in Git, public artifacts or chat. No paid storage is provisioned by this document.
3. Preserve required roles/configuration and private environment secrets separately and securely. `pg_dump` is a database backup, not a dump of cluster-wide roles, filesystem image data or server configuration. Record non-secret restore metadata and application version.
4. Quiesce application writes while capturing the database and media set. Normalized media are immutable, but selecting/replacing recommendations changes references. Capture a consistent pair; resume only after successful backup and integrity checks. Include every local image referenced by drafts and selected copies, not only currently public images.
5. Use PostgreSQL-compatible `pg_dump` custom format, check its exit code, and copy the archive plus the matching image set and checksum manifest to the private external destination. Confirm the uploaded archive can be read back and its checksum matches. A checksum stored beside an archive detects accidental corruption, not malicious replacement; it does not replace trusted storage or encryption.
6. Restore into a new empty database and separate image directory in an isolated environment, never over the serving database. Use `pg_restore --exit-on-error`; handle ownership/roles deliberately (`--no-owner` was used only for the disposable test roles). Validate Flyway history before applying any approved newer migrations.
7. Start the restored application with contact intake, SMTP, retention, visits, profile refresh and publication dispatch disabled. Restored pending messages, active delivery leases and queued build generations are real work: inspect them before enabling schedulers to avoid duplicate email or unintended builds. A restored in-memory rate limit/cache starts empty.
8. Verify counts and representative records, bilingual draft/selected-copy independence, image hashes/decoding, login permissions and rebuilding the static catalogue against the restored source. Verify identity sequences and constraints. Protect contact contents in logs and screenshots.
9. Record successful external retrieval, restore duration, recovered timestamp, checksums and test outcome without including personal data or secrets. Repeat after relevant schema/storage changes and on a schedule chosen for the recovery targets.
10. Keep the previous serving release/database and a rollback plan until the restore and release are approved. Do not move the static IP, delete the old VM or enable public intake merely because this local test passes.

References: [PostgreSQL pg_dump](https://www.postgresql.org/docs/18/app-pgdump.html), [pg_restore](https://www.postgresql.org/docs/18/app-pgrestore.html), [SQL dump backup](https://www.postgresql.org/docs/18/backup-dump.html).
