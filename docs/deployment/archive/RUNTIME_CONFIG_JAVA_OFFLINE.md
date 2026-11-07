# Historical snapshot: Java offline implementation before the stage receiver

Superseded by ../RUNTIME_CONFIG_SYNC.md. Counts, commands and blockers below are
historical and are not the current provisioning procedure.

The Python configuration-sync implementation has been replaced with Java. This
does not add a live receiver or authorize VPS provisioning/activation. Existing
production-sync remains hard-blocked with literal false. Both R2 flags remain
false; no production secret bindings/transport/artifact upload has been added.

## Layout and boundaries

Core: `src/main/java/com/ddagtech/aureliabooks/runtimeconfig/`:

- `ConfigPolicy`: explicit EV/ES mapping, complete provider snapshots, strict
  validation and deterministic ASCII Java-properties escaping. No real environment
  discovery. Secret values never enter exception messages/causes.
- `OfflineConfigStore`: disposable POSIX transaction model, generation validation,
  immutable payload digests, permissions, stage/activate/rollback/recovery and
  simulated image deployments. No external destination constructor or Docker call.
- `StagingConfigPlan`: in-memory staging overlay and candidate deployment patch.
  Google activation is a separate explicit boolean decision, default false in
  callers; payloads cannot specify profiles/imports/arbitrary Spring properties.

No Spring annotations or Spring/provider dependencies are used in this package.
These are utility classes, not application beans or microservices. JUnit tests
run in the existing Maven project without bootstrapping the application. Boot
tests use the actual 3.5.16 ConfigData loader and isolated minimal contexts:
no datasource, mail sender, S3 client or real environment sources.

Package as a separate JDK-only JAR:

```bash
./mvnw -o -B -ntp -Djava.awt.headless=true '-Dtest=RuntimeConfig*Test,RuntimeConfigurationContractTest' test
jar --create --file target/runtime-config-tool.jar \
  --main-class com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy \
  -C target/classes com/ddagtech/aureliabooks/runtimeconfig
```

The JAR entrypoint is deliberately blocked and exits 2. It cannot serialize real
credentials or activate a VPS and is not a runnable receiver/installer. Do not
install it to fix Google login. VPS-host Java 21 availability and actual adapter
packaging remain unverified. Java in the app container does not prove host Java.

## Mapping and serialization

The current 23-field allowlist supports the project's email-based phone-OTP demo;
the former Twilio integration is removed:

| Group | Environment secrets (ES) | Environment variables (EV) |
|---|---|---|
| Google | GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET | none |
| Mail | MAIL_USERNAME, MAIL_PASSWORD | MAIL_HOST, MAIL_PORT, MAIL_AUTH, MAIL_STARTTLS, MAIL_FROM |
| Phone OTP demo (sms-demo) | uses Mail group | DEMO_SMS_INBOX |
| VNPay | VNPAY_TMN_CODE, VNPAY_HASH_SECRET | VNPAY_PAY_URL, VNPAY_RETURN_URL, VNPAY_API_URL, VNPAY_VERSION, VNPAY_COMMAND, VNPAY_ORDER_TYPE, VNPAY_EXPIRE_MINUTES |
| R2 metadata only | credentials explicitly rejected | R2_ENDPOINT, R2_BUCKET, R2_UPLOAD_REQUESTS_PER_MINUTE, R2_UPLOAD_MAX_CONCURRENT |

Constrained flags R2_ENABLED/R2_UPLOAD_ENABLED may only be false. The serializer
always writes both canonical app.r2 flags false. R2 credentials are reserved and
rejected, not transferred. APP_IMAGE, DB/MYSQL, bootstrap, JVM/CLI/profile/import
settings and unknown keys are rejected. Complete provider groups are mandatory.

Google aliases require the separately activated existing google profile. Mail
uses canonical properties and requires auth/STARTTLS including starttls.required.
R2 metadata stays limited to the historical staging bucket/endpoint policy.

The sms-demo group requires the complete mail group. DEMO_SMS_INBOX maps to
demo.sms.inbox and must be one plain email address. It does not activate profiles.
StagingConfigPlan can explicitly select google, sms-demo, both or neither; no
arbitrary profile name is accepted from a configuration payload. Email OTP still
goes to the customer's email; only phone OTP is forwarded to the private team
inbox by the existing clearly labelled demo adapter. This does not verify actual
telecom delivery or phone ownership outside the academic simulation.

Keep the existing guard rejecting sms-demo with Spring profiles prod/production.
A GitHub Environment named production is not a Spring profile, but this distinction
must not be used to bypass a deployment policy. Activation on the running site
needs an explicitly reviewed demo deployment mode; no profile is changed here.

No Properties.store timestamp or platform-dependent encoding is used for payloads.
Sorted keys, LF lines, exact whitespace/backslash/control escaping and lower-case
UTF-16 escapes preserve the old properties wire format. Values are not trimmed.
Literal dollar signs are preserved. Interpolation, SpEL, NUL and unpaired
surrogates fail with static diagnostics. Input and escaped output sizes are bounded.
Java URI parsing intentionally rejects malformed/ambiguous URLs rather than
preserving permissive parser behavior.

Boot environment/system/JSON/CLI sources can override config files. The conservative
metadata-only precedence contract still requires review of higher sources; an
overlay alone cannot enforce safety against arbitrary JVM/CLI overrides.

## Permissions, transactions and OS locks

Only constructor-created `/tmp/aurelia-config-java-*` fixtures are removed by the
store. Directories start 0700, files 0600, payload/overlay become 0440 after writes,
and generations become 0750 only after assembly. Modes are explicitly normalized
under restrictive umask. Existing unsafe files/locks/symlinks fail validation.
Metadata replacements use same-directory temporary files, force/fsync and
ATOMIC_MOVE, without non-atomic fallback. Private fixture metadata now uses
properties instead of JSON. No deployed generations exist to migrate.

Java FileLock alone does not interoperate with Linux flock used by deploy.sh.
The fixture retains `/usr/bin/flock` through a bounded static shell lease. No
secret passes through arguments/environment/stdout. Lease subprocesses are
terminated/reaped on completion. Bash remains the OS layer; Python is not needed.
Actual live authorization and root/cross-UID ownership remain separate contracts.

The durable APPLYING record precedes pointer changes. Stale approvals, image
conflicts, replays, unsafe generations, pending recovery and unapproved provider
removal fail. Handled activation failure restores previous config and image;
failed recovery blocks work. Explicit rollback requires a previously confirmed
generation. Image-deployment simulations reuse the same overlay and lock.

Exception injection at seven boundaries is NOT evidence for process-restart or
power-loss atomicity. Real filesystem durability, restricted SSH receiver,
privileged resolver and server-side completion remain unverified/unimplemented.

## Workflow and validation

Only runtime-config-sync.yml changes: Python becomes Maven/JUnit plus JDK-only
JAR packaging. Existing CI/image/deploy workflows, Compose templates, database
and APP_IMAGE authority remain unchanged. JUnit checks twelve unchanged original
baseline files plus the explicitly recorded removal of Twilio YAML settings in
approved-baseline-changes.json; the original baseline manifest is retained.
Existing CI discovers tests normally. No secret/artifact upload step was added.

Bounded child regressions now launch a Java synthetic child, preserving tests of
backpressured stdin, stdout/stderr flood, timeout, interruption, kill/reap and safe
failure assertions. Reports are scanned for synthetic-secret leakage. Inventory
Bash tests use fake Docker, never the VPS or Docker daemon.

See CONFIG_SYNC_OFFLINE_RESULTS.json for counts/limits and CONFIG_SYNC_VPS_FINDINGS.md
for supplied server evidence. Historical Python review documents are under
archive/; their approvals/hashes do not apply to new Java code. New independent
review is required before committing/provisioning.

No provider performance or VPS activation result is claimed. Serialization is
in-process rather than launching an interpreter for every call; actual host JVM
startup/RSS must still be measured before provisioning.
