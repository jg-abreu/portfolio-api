# Local two-instance exercise

## Scope

`scripts/two-instance-review.py` starts an owned, disposable PostgreSQL 18 container, two actual packaged Spring Boot JVMs and a loopback Python round-robin proxy with session affinity. It uses synthetic addresses, an ephemeral shared HMAC key and a separate database. It does not use nginx, the real Cloudflare network or any VM. The configured proxy/edge ranges are documentation-only test addresses and must never be copied to production.

OAuth credentials are unused synthetic values. Gemini/Books credentials are cleared in the child environments; contact intake, SMTP, retention, publication dispatch and profile refresh are disabled. Other local services are untouched. Only visits are enabled. The script cleans up its owned processes/proxy/container in a finally block. Reports/logs go into ignored target files.

Run from the API repository after packaging the current candidate, with Java 21 in JAVA_HOME and Docker available:

```powershell
.\mvnw.cmd -B '-DskipTests' package
python scripts/two-instance-review.py
```

The packaging command is not a substitute for the relevant tests. The exercise requires a free local Docker/Java environment and deliberately launches two temporary JVMs with -Xmx256m, pool size 4 and bounded request concurrency. Heap limits do not cap total JVM process memory.

## Observed result — 2026-11-13

Both JVMs became healthy. A CSRF token acquired on one node was correctly rejected with 403 when explicitly sent to the other node. With affinity, the first registration counted once. A second independent session routed to the other node for the same synthetic visitor did not count again.

For the bounded load phase, 16 additional synthetic identities each sent six posts, with eight workers: 96 successful responses, no-store headers, exactly 16 newly counted visits. Both nodes read total 17. PostgreSQL held 17 identities, each as a 32-byte hash. No increment was lost or duplicated across instances.

The 96-post burst took 0.373 seconds, approximately 257 requests/second; response p50 27.15 ms, p95 47.54 ms, maximum 54.37 ms. This is a short warmed-up functional concurrency smoke on the local PC, not sustained-load, saturation, network or production capacity testing. It does not establish an SLA or whether the AWS VM can host two instances.

At the single Windows memory observation, combined JVM working set was about 662 MiB and combined private memory about 756 MiB, excluding PostgreSQL, Python proxy, OS and other services. These are local measurements, not VM sizing evidence.

One node was then terminated. The old token was rejected on the surviving node with 403. Acquiring a new CSRF session allowed registration to resume; the same visitor was still deduplicated, with total 17. Anonymous admin remained 401. The script does not perform a real OAuth login or demonstrate seamless authenticated-session failover.

The tested JAR SHA-256 was cc67d8a555aeba9eb9927684f37cf553c96e2380d2744a94a55d1a5f1f7aaa38. The report records the timestamp and artifact hash so it is not confused with another candidate. No serving process, real data or production configuration was changed.

## Deployment implication

Counters can coordinate through the shared database and same stable HMAC key. Current sessions are process-local: session-bound CSRF/OAuth/admin flows need consistent routing; losing a node loses its sessions. Affinity alone does not provide seamless failover or shared session storage. Do not disable CSRF to make round-robin requests pass.

This exercise is not a proposal to add a second API to the small serving VM. Keep the intended single-instance release until production resources and a justified scaling requirement are reviewed. A future multi-instance deployment needs a separately reviewed session strategy, persistence, rate-limit behavior, health routing and resource budget. No additional machine, paid service or infrastructure was provisioned here.
