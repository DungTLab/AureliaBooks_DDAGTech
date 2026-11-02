# Runtime configuration sync — offline implementation

Status: **repository implementation / offline validation only; real VPS integration BLOCKED**.
Baseline: d2baf8df47f6dabdb0eabe17e42e2a6f959c9823, branch feature/cloudflare-r2-storage. No live adapter, secret discovery, SSH receiver, Docker launcher or production activation is implemented. Do not install offline_store.py on a VPS.

## Prepared workflow and boundaries

.github/workflows/runtime-config-sync.yml is workflow_dispatch only. Its first step rejects refs other than main. The offline job has contents:read, no environment/secret bindings, no transport/artifact-upload steps, and its own concurrency group so it cannot replace a pending production deployment. The production job references environment production but has a literal false condition and a fail-closed body. The disabled job reserves the existing production concurrency group for future reviewed integration. No input/variable/example-contract flag can arm it.

All six existing workflows, three Compose files, Dockerfile and three Spring YAML files remain byte-identical; baseline-sha256.json freezes these 13 tracked files for regression checks. Existing MySQL credentials, APP_IMAGE, SSH identity, image selection and deployment commands are untouched. Existing deployment scripts are not tracked here, so no claim is made that actual future deployments already retain the overlay.

## Explicit mapping

EV means GitHub Environment variable; ES means Environment secret. Names describe the proposed source contract, not an inspected GitHub inventory. policy.render receives an explicit dictionary; it never reads os.environ, a local env file or a secret store.

| Source | Scope | Properties target | Snapshot group |
|---|---|---|---|
| GOOGLE_CLIENT_ID | ES | GOOGLE_CLIENT_ID | google |
| GOOGLE_CLIENT_SECRET | ES | GOOGLE_CLIENT_SECRET | google |
| MAIL_HOST | EV | spring.mail.host | mail |
| MAIL_PORT | EV | spring.mail.port | mail |
| MAIL_USERNAME | ES | spring.mail.username | mail |
| MAIL_PASSWORD | ES | spring.mail.password | mail |
| MAIL_AUTH | EV | spring.mail.properties.mail.smtp.auth | mail |
| MAIL_STARTTLS | EV | spring.mail.properties.mail.smtp.starttls.enable | mail |
| MAIL_FROM | EV | app.contact-verification.mail-from | mail |
| VNPAY_TMN_CODE | ES | vnpay.tmn-code | vnpay |
| VNPAY_HASH_SECRET | ES | vnpay.hash-secret | vnpay |
| VNPAY_PAY_URL | EV | vnpay.pay-url | vnpay |
| VNPAY_RETURN_URL | EV | vnpay.return-url | vnpay |
| VNPAY_API_URL | EV | vnpay.api-url | vnpay |
| VNPAY_VERSION | EV | vnpay.version | vnpay |
| VNPAY_COMMAND | EV | vnpay.command | vnpay |
| VNPAY_ORDER_TYPE | EV | vnpay.order-type | vnpay |
| VNPAY_EXPIRE_MINUTES | EV | vnpay.expire-minutes | vnpay |
| TWILIO_ACCOUNT_SID | ES | app.contact-verification.twilio-account-sid | twilio |
| TWILIO_AUTH_TOKEN | ES | app.contact-verification.twilio-auth-token | twilio |
| TWILIO_FROM | ES | app.contact-verification.twilio-from | twilio |
| R2_ENDPOINT | EV | app.r2.endpoint | r2 |
| R2_BUCKET | EV | app.r2.bucket | r2 |
| R2_UPLOAD_REQUESTS_PER_MINUTE | EV | app.r2.upload-requests-per-minute | r2 |
| R2_UPLOAD_MAX_CONCURRENT | EV | app.r2.upload-max-concurrent | r2 |

R2_ENABLED and R2_UPLOAD_ENABLED are constrained EV inputs: omitted means false; any other value is rejected. Both canonical app.r2 properties are always false. R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY are reserved ES names and are rejected from current payloads, not fetched or transferred. R2 metadata is limited to the historical staging bucket and default-jurisdiction endpoint pattern; changing that scope requires review.

GOOGLE_CLIENT_ID/SECRET are property aliases consumed by existing application-google.yaml. They do not create a registration without the separately enabled google profile. No profile, config-import, bootstrap, DB, MYSQL or APP_IMAGE key is accepted from the payload. Provider groups require a complete snapshot; incomplete/undeclared groups fail. Normal activation cannot remove an existing provider group. Explicit rollback can select a previously confirmed snapshot and therefore restore its previous provider set.

Mail currently permits authenticated SMTP with required STARTTLS; it is not an approved SMTPS or unauthenticated-relay policy. HTTPS provider URLs reject userinfo, queries, fragments, malformed ports and whitespace. Provider ownership/callback/TLS/sender facts remain external approval requirements. These shape checks do not validate real credentials or provider reachability.

## Serialization and precedence

The serializer writes ASCII Java properties with escaped whitespace, control characters, backslashes and UTF-16 Unicode sequences. Secret values are never trimmed. Dollar signs are preserved. Values containing literal dollar-open-brace, SpEL hash-open-brace, NUL or unpaired surrogates are rejected with a static code/key name; they are not silently changed. Such credentials need a separately reviewed representation policy, not an automatic rotation.

The overlay uses required SPRING_CONFIG_ADDITIONAL_LOCATION, not replacement spring.config.location. A real installer must preserve any reviewed existing additional locations; current template is BLOCKED if such a contract is unknown. It sets both R2 environment flags false, adds a nonsecret generation label and a read-only mount of one immutable release. It declares no image, DB, ports, entrypoint, command or other service. Missing mount paths must not be auto-created.

Boot tests use the actual 3.5.16 ConfigData loader and Python serializer, with synthetic property sources and classpath-only base configuration. Two minimal SpringApplication tests register only an empty TestConfiguration: no auto-configuration/component scan, web server, datasource, mail sender or S3 beans. They verify real JSON postprocessing and command-line insertion. The standard systemProperties insertion marker is present but contains no real system properties. Native environment, JSON, system and CLI sources can override a file; existing aliases alone have different behavior. The metadata-only precedence contract conservatively rejects unknown environment names/JVM/CLI overrides. Standard image ENV names and any actual profile settings need reviewed extensions before real integration.

## Disposable transaction model

OfflineStore owns a fresh private directory under /tmp; its constructor accepts no existing destination. It uses actual flock, exclusive generation creation, no-follow file reads, ownership/mode checks, private metadata and file/directory fsync. No network/process launcher exists in the store. Fixture credentials are randomly generated per run, held only in memory/protected temporary properties files and removed at fixture teardown.

The receiver role may only stage; the installer role may activate/recover/rollback/simulate an image deployment. These are model capabilities, not an implemented SSH/Unix authorization system. Properties/overlay files are mode 0440; metadata is 0600; the fixture owner stands in for privileged ownership. Actual root ownership and cross-UID application access remain BLOCKED.

Activation checks expected current generation, image identity, immutable content, provider groups and stale/replayed requests before changing pointers. A durable APPLYING record precedes pointer changes. Confirmation updates generation history. Failed recreation/confirmation restores the prior generation and the same image. A rollback failure records RECOVERY_FAILED and blocks further operations. Recovery handles injected termination before journal, after journal, after pointer, after recreation, before confirmation, after confirmation and after commit. An explicit rollback may only target a previously confirmed immutable generation.

Image deployment simulation takes the identical host lock and validates/reuses the active overlay. It can change only the synthetic image identity; database identity remains sealed. A real subsequent deployment must follow this contract in every entry point. Tests prove the fixture behavior, not compliance of the absent deploy.sh. Crash injection does not prove actual VPS power-loss/filesystem durability.

## Offline checks

From the repository root:

```bash
python3 -I -B -m unittest discover -s scripts/runtime-config/tests -v
./mvnw -o -B -ntp -Djava.awt.headless=true -Dtest=RuntimeConfigurationContractTest test
```

Use -B to avoid Python bytecode artifacts. Only synthetic inputs are supplied; no provider/application/QA-server bootstrap is used. Surefire reports contain test/status metadata, never payloads. Java assertions/log capture and Python stdout/stderr/fixture checks detect secret disclosure without printing values. No workflow uploads test payloads or reports. See CONFIG_SYNC_OFFLINE_RESULTS.json for exact final counts; focused runs overlap and must not be added together.

YAML syntax/structure was separately checked with locally installed PyYAML BaseLoader. actionlint is unavailable, so GitHub-specific/actionlint validation is not claimed. Existing CI is not modified; these commands and the prepared manual workflow run the new tests. The existing Maven suite will also discover the new Java class normally.

## VPS provisioning plan — NOT EXECUTED

1. Obtain separate permission for a metadata-only inventory: exact Compose file list/project/service names/version, deploy/rollback entry points, current image ownership, container UID/GID, environment key names, profiles, config import locations, SSH policy, filesystem/backup capabilities and existing health checks. Never request credential values or dump resolved Compose/docker environments.
2. Verify production Environment branch restrictions/reviewers/plan support and source name inventory. New workflow dependencies must be reviewed/pinned before arming a secret-bearing job. Preserve the existing deployment SSH identity. Provision a separate restricted configuration receiver identity only after approval.
3. Review/implement a bounded receiver and root-owned installer, not included here. Force a fixed command; deny PTY/forwarding; no Docker group; narrowly scoped privilege delegation. Reject duplicates, path traversal, symlinks, unknown actions and arbitrary properties. Validate source revision, expiry, expected generation/image and exact staged payload. Example contract flags cannot replace evidence.
4. Agree an unused read-group GID and exact host root outside the checkout/build context. Real files must be root-owned, app-readable only, and immutable to app/receiver users. Mount only the selected generation, not archives. Retain restricted/encrypted backups for an approved period. Compose file-secret uid/gid attributes are not a substitute for host permissions.
5. Integrate the additive overlay into actual deploy.sh/rollback/manual entry points, with a shared host lock and pending-transaction recovery. Keep the existing compose environment/MySQL/APP_IMAGE authority intact. Review merge behavior and any existing additional locations; do not copy the repository's fixed-test-image Compose file over VPS configuration.
6. Implement server-side completion/recovery that survives SSH/runner loss. Stage, validate and fsync an immutable generation, promote atomically, recreate only app with no pull/build/dependency recreation, and verify unchanged image identity plus generation mount/label and HTTP readiness. Never rely on file replacement as hot reload. No zero-downtime guarantee is made.
7. Rehearse with disposable synthetic infrastructure first. Obtain separate approval to stage real configuration and another explicit generation-specific activation/rollback window. Freeze source edits while capturing a snapshot. Coordinate credential rotation: rollback cannot revive revoked credentials; VNPay in-flight transactions need provider-specific planning.
8. Keep both R2 flags false. Any R2 credentials/enablement, live provider smoke, production activation or image change is a separate review/approval. No generic truthy switch may bypass these gates.

Existing HTTP / checks establish basic availability, not provider credential validity. Validate known callback/proxy settings and provider enablement policy without sending SMS/mail/payment requests as readiness probes. Production integration remains BLOCKED until these contracts and rollback evidence are reviewed. This task stops at offline validation; no commit/push/PR/merge is authorized.


## Remediation Round 1 — awaiting second independent review

Authorization: LOCAL FIXES ONLY, exactly the two MAJOR findings and one MINOR subprocess finding. HEAD remains d2baf8d; no staging/commit/push/PR/merge, production workflow, real secret lookup, VPS or provider operation occurred.

- Leakage assertions now receive only boolean conditions plus fixed messages. The audit also replaced membership assertions that could dump a secret-bearing properties body when a marker check failed, and error-code comparisons that could print unexpected exception text. Three regressions run deliberately failing nested unittest cases entirely in RAM (text, binary and missing-property marker) and verify the formatted failure reports contain no dynamic synthetic secret. They use the same assertion helpers as the suite; no captured failure body is printed or saved.
- New directories/files are created privately (0700/0600). Newly created descriptors are normalized explicitly, properties/overlay become 0440 only after writing/flushing, and the assembled generation becomes 0750 only after all files are ready. JSON promotion files remain 0600. Existing unsafe locks/files still fail validation rather than being repaired into an accepted state. Regressions cover a full activation/failure rollback/injected-exception recovery/explicit rollback under umask 077, creation/promotion observations under umask 000, and rejection of an existing unsafe lock. These are fixture checks, not root/cross-UID VPS permission evidence.
- The Java subprocess helper writes stdin and drains bounded stdout/stderr concurrently. One deadline covers all I/O plus process completion; an I/O/output-limit failure terminates the operation early. Failure diagnostics use static codes without original causes/child output. Cleanup kills/reaps the child and joins its I/O executor within a separate two-second grace; caller interruption is preserved after cleanup. New regressions cover a silent hung child, backpressured stdin, stdout flood, stderr flood, timeout after secret-bearing stderr, a nonzero exit failure report, and interruption. Synthetic bytes use private stdin pipes, never process arguments.

Final configuration-sync suites: **47 Python + 25 Spring = 72 tests PASS, 0 failures/errors/skips**, including all previous 59 tests and 13 new regressions. The complete Python suite also passed again under umask 077; this repeated run overlaps and is not added to the total. Python AST syntax, two YAML structures and workflow guards passed. Five generated diagnostic files were scanned for the dynamic-secret prefix without finding it; no Python bytecode artifacts remain. actionlint/full application suite/GitHub CI were not run.

The manual workflow, production hard block, EV/ES policy, R2=false flags, Google profile behavior, overlay template and all 13 tracked baseline files remain unchanged. Only the three reviewed source/test files and these two documentation/result files changed in this round. See CONFIG_SYNC_OFFLINE_RESULTS.json for current hashes and the pre-remediation hash snapshot.

**Code review is not self-approved.** Real integration remains BLOCKED. Process-restart recovery and power-loss atomicity are still unproven; existing exception-injection tests have not been upgraded into evidence for either property. Stop here for second independent code review before any further authorization.
