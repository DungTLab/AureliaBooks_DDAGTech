# Runtime configuration: validate and stage an immutable snapshot

Status: repository-side stage sender/receiver implemented and offline-tested.
On 2026-11-05 the operator reported installing the staging receiver and obtaining
READY_STAGE_ONLY_V1, including a passwordless sudo check. This is user-supplied
evidence, not an installation performed or independently verified by this agent.
**No activation/deployment command exists.** No snapshot transfer has been reported.

## What changed compared with main

The original main baseline `63d83a6707b02ac1cfb41c20cdfee4112e41e948` has only the offline
Python workflow. The local change replaces that workflow with one manual workflow
containing two jobs: offline validation and optional stage-snapshot. It does not
add another workflow. The subsequent repository-wide Java migration also replaces
the catalog CI command and inline validation in automatic staging/promotion with
Java tools. Environment protections, deployment locks, image selection and SSH
deployment entrypoints are preserved. See JAVA_TOOLING.md for that migration.

Historical review receipts are under archive/. They do not certify this receiver.
Historical baseline hashes remain evidence; permanent CI no longer freezes them,
because legitimate teammate changes (such as the VNPay Compose PR) must be allowed.
Tests instead check the actual workflow/environment/entrypoint contracts.
OfflineConfigStore/FakeApplication are test-source fixtures, not production JAR
content. The unused deployment patch invoking a nonexistent resolver is removed.

## Manual workflow inputs

Workflow: **Runtime Configuration - Validate or Stage Snapshot**.

- Branch: main only. `operation=validate` (default) never accesses Environment
  secrets. `operation=stage` requires successful tests first.
- `target_environment`: staging or production; selects the corresponding GitHub
  Environment and its protections. The job checks the approved SHA against main
  HEAD after Environment approval and before creating the payload.
- Actions are pinned to verified commit SHAs. Environment branch restrictions must
  still limit deployment to main. Required-reviewer and bypass choices need owner
  review. No automatic/manual production image deployment is triggered here.
- Separate stage concurrency groups avoid displacing pending image-deploy jobs.
  Inactive snapshots do not affect concurrent image deployment. A future activation
  implementation must coordinate with the existing host image-deployment lock.

## Explicit bindings

| Source | Scope | Consumer |
|---|---|---|
| VPS_HOST, VPS_USER, VPS_SSH_KEY, VPS_SSH_HOST_KEY | ES | runner SSH transport only |
| GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET | ES | Google aliases in properties |
| MAIL_USERNAME, MAIL_PASSWORD | ES | canonical Spring mail properties |
| VNPAY_TMN_CODE, VNPAY_HASH_SECRET | ES | canonical vnpay properties |
| MAIL_HOST, MAIL_PORT, MAIL_AUTH, MAIL_STARTTLS, MAIL_FROM | EV | canonical mail properties |
| DEMO_SMS_INBOX | EV | demo.sms.inbox; requires complete mail group |
| VNPAY_PAY_URL, VNPAY_API_URL, VNPAY_RETURN_URL, VNPAY_VERSION, VNPAY_COMMAND, VNPAY_ORDER_TYPE, VNPAY_EXPIRE_MINUTES | EV | canonical vnpay properties |
| R2_ENABLED, R2_UPLOAD_ENABLED | fixed false in workflow | canonical app.r2 flags |

The workflow transfers all four project groups together (Google, Mail, VNPay,
phone-OTP email demo). Missing/invalid groups fail; no real credentials are read
by offline tests. Twilio is removed. DB/MYSQL, APP_IMAGE, bootstrap settings,
arbitrary properties/JVM/profile/import keys are rejected.

R2 credentials and metadata are deliberately not transferred while R2 is disabled.
The generic policy reserves/rejects R2 credentials. A future R2 enablement requires
its separate scope review; storing those secrets in GitHub does not activate them.
Profiles are not activated by receiving a snapshot. Phone OTP demo still requires
an explicit sms-demo profile choice; existing prod/production profile guards remain.
No SMS is sent and no real phone ownership is verified by the email simulation.

## Data flow and safe failures

1. Runner verifies main SHA and compiles only the JDK-only runtimeconfig sources.
2. stage.sh creates private temporary SSH key/known_hosts files (umask 077), checks
   the fixed receiver command is provisioned, and removes those key files on exit.
3. Java reads only the explicit provider allowlist, validates and serializes the
   snapshot in memory. Frame includes target environment, stage-only action,
   generation, source SHA and five-minute expiry.
4. A private stdout-to-SSH-stdin pipe sends the frame. No payload file, build arg,
   environment dump, workflow output or artifact upload is used. The SSH child has
   a cleared environment and explicit host-key checking; remote diagnostics are
   suppressed and unexpected output is never echoed.
5. Root receiver verifies environment/revision/expiry, bounds stdin to 128 KiB and
   ten seconds, revalidates payload policy, then publishes a protected immutable
   generation. Duplicate generations fail; no overwrite or automatic retry.

Generation IDs are safe non-secret receipts and are logged BEFORE sending. Success
prints STAGED_NOT_ACTIVE and the same ID, never a credential or secret-derived
hash. On uncertain SSH failure do not generate a replacement blindly: a privileged
status check of that exact environment/SHA/generation verifies immutable hashes
and returns only MISSING or STAGED_NOT_ACTIVE. Inspect incomplete private pending
directories through an authorized operator; never bulk-delete them automatically.

This protocol relies on authenticated SSH; it is not itself an encryption/signing
scheme. JVM strings cannot be reliably zeroed. Core dump, swap and backup policies
must be reviewed during provisioning. A receiver host under an attacker's control
cannot be made safe by log masking.

## Receiver contract and filesystem

Candidate wrapper: deployment/runtime-config/aureliabooks-runtime-stage.
Fixed executable installed later: /usr/local/sbin/aureliabooks-runtime-stage.
Fixed tool: /usr/local/lib/aureliabooks-runtime/runtime-config-tool.jar.
The wrapper only allows check/stage/status and validated environment/SHA/ID args;
it clears JVM environment before running Java. No arbitrary paths, Java options,
shell programs or Docker operations can be submitted in the payload.

Root-owned storage is outside both the user-owned deployment tree and checkout:

```text
/var/lib/aureliabooks-runtime/
  staging/                         # root:root 0700
    stage.lock                     # root 0600
    releases/                      # root:root 0700
      <generation>/                # root:999 0750
        runtime-integrations.properties  # root:999 0440
        metadata.properties              # root 0600, never emitted
  production/                      # same separate layout
```

Owner 0 and non-writable/symlink-free parents are required on the real path. The
app GID 999 comes from supplied container metadata and must be rechecked during
installation. The receiver never creates/repairs an unsafe preexisting root.
It uses exclusive creation, bounded input, file/directory fsync and atomic directory
promotion. There is no active pointer or Compose overlay installed by this step.
Protected metadata records STAGED_NOT_ACTIVE and the private payload digest.

The stage-only FileLock coordinates stage writers; it is NOT claimed compatible
with deploy.sh's flock, and need not be because it cannot recreate app or change
the active configuration. Activation will need the actual shared host lock.

## Provisioning checklist — NOT EXECUTED

Do not run the stage workflow until the reviewed receiver is installed.

1. Owner approves a provisioning window and reviews this code/tool hash. Host
   Java is currently reported absent; provision a maintained Java 21 headless JRE
   using the host OS's package policy (not a JDK/Maven or Spring Boot server).
2. Verify numeric app UID/GID remains 999:999 and trusted root parent directories.
3. Install the reviewed JAR root:root 0644 under a root:root 0755 tool directory;
   install the wrapper root:root 0755. Neither may be writable by the SSH user.
4. Create /var/lib/aureliabooks-runtime root:root 0755, environment roots and their
   releases directories root:root 0700. Do not change /opt/apps .env or MySQL.
5. Grant only the fixed wrapper via a narrowly reviewed sudoers rule, validated
   with visudo. Existing SSH identity is preserved; never grant arbitrary java,
   shell, install or Docker commands as part of this feature.
6. Prefer a separate restricted configuration identity for stronger isolation.
   Current deployment identity already controls Docker; do not claim it is
   privilege-isolated from production. Reusing it preserves existing trust but
   does not remove its preexisting root-equivalent Docker capabilities.
7. Verify check/status as the actual SSH user, rehearse a synthetic snapshot, then
   approve one real staging transfer. Production has its own Environment approval.

Build the standalone tool without starting the app:

```bash
mkdir -p target/runtime-config-standalone
javac --release 21 -d target/runtime-config-standalone src/main/java/com/ddagtech/aureliabooks/runtimeconfig/*.java
jar --create --file target/runtime-config-tool.jar \
  --main-class com.ddagtech.aureliabooks.runtimeconfig.RuntimeConfigCli \
  -C target/runtime-config-standalone .
```

Do not invoke prepare in a terminal containing real provider secrets: its stdout
is the private wire payload and is intended only for the reviewed stage.sh pipe.
No live installation or transfer is authorized by this document alone.

## Activation and rollback remain separate

This code does NOT resolve the running website's missing Google/Mail credentials
until a separately reviewed installer mounts a selected generation and recreates
app. Existing image deployments/no-op behavior and database are unchanged.
No production deploy, automatic restart, live provider request or R2 operation is
performed by stage. Stage has no active-state rollback because it changes no active
state; an unsuccessful transfer leaves existing app configuration unchanged.

Real app activation/rollback, image-deploy overlay persistence, actual cross-UID
mount access, server restart/power-loss recovery and current production Compose/
proxy contracts are not verified. Disposable OfflineConfigStore tests cannot
certify those missing pieces. Do not install an old patch referring to the removed
nonexistent resolver. Historical hashes/review receipts are not new code approval.
