# R2.37.2 — QA E2E runner

Prerequisites: Java 21/Maven wrapper, Python 3, Docker, and the existing `aureliabooks-r2-mysql-qa` container with reviewed canonical schema in `r2_image_qa`. Never point this runner at application/staging/production DBs. No Cloudflare account/credentials are used.

```bash
# From repository root; starts QA servers, local Nginx, runs 28 HTTP cases, then stops/cleans up:
python3 scripts/r2-e2e/run_checks.py
```

The bootstrap reads QA credentials outside Git, checks the named Docker loopback mapping, fixes JDBC host/database/user, verifies SELECT DATABASE()/CURRENT_USER(), uses `ddl-auto=validate`, disables SQL init and avoids the production component scan. It imports real JPA repositories, product/master services, mappers, controllers, MVC advice and SecurityConfig. Only storage and account identities are synthetic. It asserts no S3Client exists. A test-only transactional decorator injects failure **after EntityManager.flush(), before commit** to verify actual SQL rollback for all four create/update paths.

The official Nginx image is pinned by digest in proxy.py. The proxy runs read-only, listens only on dynamically chosen loopback ports, and routes `/qa` to the two isolated contexts (upload on/off). This requires Linux Docker host networking; it is not a VPS deployment. Do not reuse this test config as a production proxy configuration.

Outputs (ignored under target/):

- `r2-persistence-http-results.json` — functional HTTP summary.
- `r2-persistence-db-evidence.json` — committed QA rows before cleanup.
- `r2-persistence-cleanup.json` — baseline and final counts.
- `r2-persistence-server.log`, `r2-persistence-http.log` — local diagnostics, **do not publish raw logs**.
- `r2-persistence-qa.json` / `r2-qa-proxy.json` — ephemeral URL/container metadata, removed on cleanup.

For manual browser verification:

```bash
bash scripts/start-r2-persistence-qa.sh
# In a second terminal, after target/r2-persistence-qa.json exists:
python3 scripts/r2-e2e/proxy.py start
python3 scripts/r2-e2e/http_checks.py
# Open target/r2-qa-proxy.json's url + /internal/login in a browser.
# Use synthetic identities/password from the harness source, not any real account.
# Use the fixture IDs from target/r2-persistence-qa.json in dropdowns.
# Stop explicitly within the 30-minute lease:
touch target/r2-persistence-qa.stop
python3 scripts/r2-e2e/proxy.py stop
```

A timeout is deliberately a JUnit failure, not PASS. Cleanup still runs in finally. Runner success requires HTTP report PASS, explicit stop, JUnit success and cleanup count equality. If a process is forcibly killed or DB cleanup fails, inspect the exact run prefix/IDs before any manual action; never truncate tables, drop the QA DB, or delete unrelated rows as a shortcut.

The test-only CSRF page `/products/qa-session` renders the real login template without authenticated-entry redirects; it lets HTTP clients obtain a valid token for wrong-role tests. Fault/state routes live under `/manager/qa-*`, protected by MANAGER + CSRF. None of these routes/classes are in the production artifact. Never enable this harness on a non-loopback interface.
