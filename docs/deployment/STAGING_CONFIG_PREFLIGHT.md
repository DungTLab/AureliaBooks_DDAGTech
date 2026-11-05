# Staging configuration integration — preflight

Historical preflight checkpoint. The current sender/receiver candidate and
remaining provisioning/activation gates are documented in
[RUNTIME_CONFIG_SYNC.md](RUNTIME_CONFIG_SYNC.md). Nothing here confirms live installation.

The merged configuration sync implementation is offline only. Re-running image
deployment does not transfer GitHub integration variables or secrets.

## Read-only inventory (manual)

From the laptop repository, using your existing SSH destination:

```bash
ssh <existing-vps-alias> 'bash -s -- staging' < scripts/runtime-config/inspect_vps.sh
```

Replace the alias locally. The script is streamed over stdin, not installed.
It reads file permission metadata and Docker metadata, not file contents, logs
or secret values. Docker templates emit only fixed presence/decision markers.
It performs no HTTP/provider request and no deployment. Docker access must already
exist; do not add the account to the Docker group just to run this check.

Do not use `bash -x`, `docker inspect` without the supplied templates, resolved
`docker compose config`, `printenv`, or `cat .env` for sharing diagnostics.

PRESENT means an environment key exists, not that it is nonempty/valid or the
effective Spring property. MISSING can be legitimate when properties come from
a mounted file. Profile markers inspect only SPRING_PROFILES_ACTIVE; other
profile sources and config paths remain unverified. The runtime mount marker
checks only the proposed mount destination. File absence can also mean lack of
directory traversal permission. This inventory cannot approve activation.

## Remaining contracts before implementing the live adapter

- Exact staging Compose files, project/service names and deployment/rollback
  entrypoints; reviewed sanitized script structure (no credentials).
- Application numeric UID/GID and approved configuration reader group.
- Existing profile/config location/CLI/JVM precedence and provider configuration
  sources. Enable Google explicitly only after reviewing existing profiles.
- Shared host lock and recovery across all image/config deployment operations.
- Restricted receiver identity and privileged installer permissions.
- Environment staging branch protection/review and source key inventory.
- Approved callback domains and provider sandbox selection.

Implement and rehearse the adapter with disposable synthetic fixtures after
these contracts are established. Keep production-sync hard-blocked and both R2
flags false. Production credentials/activation and live provider requests remain
separate gates. Do not install the disposable OfflineConfigStore on the VPS.
