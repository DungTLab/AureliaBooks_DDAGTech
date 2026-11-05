# Staging configuration envelope — offline increment

Historical protocol checkpoint. The current protocol also binds production
explicitly, with a stage-only receiver candidate. See
[RUNTIME_CONFIG_SYNC.md](RUNTIME_CONFIG_SYNC.md); historical counts and absent-receiver
statements below describe that earlier increment only.

The user supplied PASS for the Java migration in chat on 2026-11-03. That decision
is recorded as user approval, not a new independent audit performed by this agent.
The new protocol below is a subsequent increment and is not covered by that PASS.

## What is implemented

`StagingConfigEnvelope` provides deterministic, bounded binary encoding/decoding
of a staging-only, stage-only configuration request. It reuses the reviewed Java
allowlist/serializer. There is no environment discovery, executable CLI, SSH
connection, Docker call or application startup.

Frame: version magic, environment, action, generation, source revision, issued
and expiry epoch seconds, sorted unique groups, sorted unique key/value pairs.
Strings are length-prefixed UTF-8, strictly decoded. Maximum frame is 128 KiB;
group count is at most four, field count is at most the 23 allowlist fields plus
two constrained flags. Strings/payloads also satisfy existing policy bounds.
Truncation, malformed UTF-8, duplicate/unsorted keys/groups and trailing bytes fail
with static diagnostics that do not retain a cause or rejected value.

Expiry is at most 300 seconds after issue. A supplied Clock enforces issue <= now
< expiry; future/expired requests fail. A separately supplied expected revision
must match; a live installer must derive it from trusted approval/attestation,
not from the untrusted request itself. The binary frame is not encrypted/signed:
the future transport must provide authentication/confidentiality.

`receive` accepts a complete in-memory byte array and an OfflineConfigStore,
then stages an immutable generation without activation. A replay fails because
the generation already exists in that fixture. The role argument models trusted
receiver context; it is not a replacement for OS/SSH authorization. The decoded
values are revalidated before staging. Object toString is fixed/redacted. Never
log or retain the encoded frame; credential strings cannot be reliably erased
from JVM memory, and protected memory/core-dump policy needs host review.

## Offline evidence

107 configuration-sync tests PASS, 0 failures/errors/skips. This includes eight
new protocol tests: exact secret round-trip in private fixture files, no automatic
activation, replay, expiry/future/revision conflict, lifetime/groups, environment/
action/role rejection, framing/UTF-8/size/duplicate rejection and redaction.
No synthetic-secret prefix appears in the configuration-sync Surefire reports.

The previous migration's repeated 99-test umask runs and 22 R2 regression tests
remain historical evidence. They were not rerun for this protocol-only increment
and are not added to the 107-case current suite count. Current shell/YAML/workflow
contracts remain part of that suite. No existing deployment workflow was changed.

## Remaining real integration work

- Deadline-bounded stdin framing and authenticated SSH transport. A pre-read byte
  array with a size cap does not prove that a hostile input pipe cannot hang.
- Host Java 21 runtime/packaging verification and restricted receiver identity.
- Root-owned immutable stage storage and separately approved installer/resolver.
- Shared lock and recovery in actual deployment/rollback entrypoints.
- Generation-specific activation preserving APP_IMAGE/DB/proxy/uploads, health
  checks and separately tested rollback after runner/SSH/process loss.
- GitHub staging Environment bindings/protections and live-provider approval.

The main entrypoint and production-sync job remain blocked. This is not a live
receiver and does not fix the running site's missing Google configuration yet.
No VPS provisioning, real credentials, provider calls, commit/push or workflow
execution occurred. Next server fact required: whether the VPS host has Java 21
(the Java runtime inside the app container is insufficient evidence).
