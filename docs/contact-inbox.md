# Private contact inbox

The inbox uses the existing `contact_messages` outbox table. Migration V5 adds independent read/archive timestamps and an optimistic version. Marking a message read or archiving it never changes delivery status, attempts or scheduling. Archiving is reversible and does not delete visitor data.

All routes require the GitHub ADMIN session. Mutations also require the existing CSRF token. Responses inherit Spring Security no-store headers. No visitor data is logged by these components.

| Method | Route | Contract |
| --- | --- | --- |
| GET | `/api/admin/messages?archived=false&page=0&size=20` | Newest first, maximum 50 items. Returns messages, page, size, totalElements and hasNext. Summaries omit email and message body. |
| GET | `/api/admin/messages/{id}` | Full message and current version. Reading does not automatically mark it read. |
| PATCH | `/api/admin/messages/{id}/read` | JSON value (boolean) and version (nonnegative integer). |
| PATCH | `/api/admin/messages/{id}/archive` | Same body; false restores to inbox. |

Mutations return the updated detail; stale versions return 409, missing messages 404 and invalid input 400. The client must use the returned version for its next change.

Contact submission and delivery are implemented behind separate disabled-by-default flags; the public form stays disabled. See contact-delivery.md for the enabled contract, protections, retry policy and 90-day retention. Inbox actions remain independent of delivery.

The frontend feat/v2 admin includes the Inbox view in EN/PT, using plain text rendering, explicit read/archive actions and pagination. Real browser validation of this new view is still pending. Backend validation: 74 tests passed on 2026-11-13; the initial repackage failed because the previous local JAR was open, then packaging passed after stopping that local process.
