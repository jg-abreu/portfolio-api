# Approximate visits

The feature is disabled unless PORTFOLIO_VISITS_ENABLED=true. It reuses the existing visits and visit_counter tables; no migration or production change is part of this stage. GET /api/visits reads {total}; POST /api/visits returns {total,counted}. Both responses use Cache-Control: no-store. POST retains normal CSRF protection; the caller must obtain /api/auth/csrf with the same session cookie first.

## Counting and privacy

One IP can count again exactly 30 minutes after its last counted visit. Requests during the window do not move its timestamp. A single transaction performs INSERT ON CONFLICT with a due-time predicate and increments the singleton total only if a row was inserted or updated. Requests for the same IP serialize correctly; increments for different IPs are not lost. JdbcClient is used for the atomic SQL rather than adding redundant JPA mappings.

Only HMAC-SHA256 of the canonical address bytes and last-counted timestamp are stored, never the raw address. Equivalent IPv6 forms (and mapped IPv4 forms) share an identity. Hashes at least 30 minutes old are removed by a one-minute scheduled sweep; they may remain until the next sweep. Cleanup never resets the total. All API instances need the same database and HMAC key.

Empty or obvious crawler/headless/tool user agents are skipped without storing a hash. This is a heuristic, not authentication or comprehensive bot detection: agents can be forged, IPs can be shared/change, and the number is approximate visits rather than unique people. Edge rate limiting remains a deployment concern.

## Private configuration

- PORTFOLIO_VISITS_ENABLED=true
- PORTFOLIO_VISITS_HMAC_KEY: privately generated random secret of at least 32 characters; never commit it or place it in browser variables.
- PORTFOLIO_VISITS_IP_SOURCE=DIRECT for direct local connections. It ignores all forwarded headers.
- PORTFOLIO_VISITS_IP_SOURCE=CLOUDFLARE_PROXY for the intended Cloudflare → nginx → API topology.
- PORTFOLIO_VISITS_TRUSTED_PROXIES: explicit CIDRs for the actual immediate nginx/Docker peers seen by the servlet. Determine these in the target environment; do not guess them or trust every address.
- PORTFOLIO_VISITS_CLOUDFLARE_CIDRS: current verified IPv4/IPv6 Cloudflare ranges, maintained from https://www.cloudflare.com/ips/ . No permissive network defaults are included.
- SERVER_FORWARD_HEADERS_STRATEGY=NONE: the counter resolves its own trusted chain and refuses conflicting framework rewriting when enabled.

Cloudflare mode accepts CF-Connecting-IP only if the immediate socket peer matches the configured proxy CIDRs and nginx's overwritten X-Real-IP belongs to the configured Cloudflare ranges. Missing, malformed or untrusted hops fail without counting. IP parsing accepts numeric literals only, without hostnames, zones or comma-separated lists. nginx must overwrite X-Real-IP with its actual Cloudflare peer, preserve the edge-controlled CF-Connecting-IP and keep the API port private. Confirm the Cloudflare-only edge firewall and any real-IP rewriting before enabling. These are prerequisites, not claims of current VM readiness.

Keep the HMAC key stable across deploys/restarts; it is not rotated automatically. Rotation starts new deduplication identities and can count an address again during the existing window. For a planned rotation without overlap, suspend registration for at least 30 minutes before switching the key; retain the total. A compromised key needs a separately reviewed incident response. Back up private configuration separately from source code.

## Validation

PostgreSQL integration checks cover first/repeat/exact-boundary counts, unchanged timestamps during the window, concurrent same/different hashes, expiry without resetting the total, exact HMAC bytes, normalized IPv6, bot skipping, CSRF and trusted/untrusted proxy hops. An additional embedded-Tomcat HTTP check uses real cookies and a CSRF token and verifies two registrations produce one count. SMTP remains disabled in these tests.

The complete suite passed 93 tests before the extra HTTP case was added; the focused visit suite was then rerun with eight cases. An existing manual outbox test now explicitly makes its fixture due before calling the worker, removing an immediate-clock assumption without changing delivery code. No server, production counter, HMAC credential or deployment was configured.
