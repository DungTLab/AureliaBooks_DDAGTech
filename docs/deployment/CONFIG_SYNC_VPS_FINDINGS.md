# Configuration sync: observed VPS contracts

Historical observation and design checkpoint. The generated deploy-script patch
described below has been removed. A stage-only receiver now exists as a local
candidate; it has not been installed. Use [RUNTIME_CONFIG_SYNC.md](RUNTIME_CONFIG_SYNC.md)
for current instructions; do not install a patch from this document.

Recorded 2026-11-03 from user-supplied metadata and complete deployment scripts.
This is reported evidence, not a direct VPS inspection or deployment approval.

## Confirmed observations

| Contract | Staging | Production |
|---|---|---|
| Root | `/opt/apps/swp391-staging` | `/opt/apps/swp391` |
| Compose project | `aurelia-staging` | `aurelia-production` |
| Service | `app` | `app` |
| Container | `aurelia-staging-app-1` | `aurelia-production-app-1` |
| Container user | `appuser`, UID 999, GID 999 | `appuser`, UID 999, GID 999 |
| Compose file order | `compose.yaml` | `compose.yaml`, `compose.caddy.yaml` |
| Deployment entrypoint | `scripts/deploy-staging.sh` | `scripts/deploy.sh` |
| Lock | `scripts/deploy.lock`, nonblocking flock on FD 9 | same relative path, separate root |
| Readiness | expected image, running state, HTTP `/` on 18080 | same checks on 8080 |

User-reported Compose version: 5.3.1. Both supplied scripts use ARM64 SHA-tagged
GHCR images, back up `.env`, change exactly one `APP_IMAGE` entry, recreate only
`app` with `--no-deps`, and restore the old `.env`/image on handled startup or
readiness failure. Deployment identity and image ownership must be preserved.

Reported permissions: staging Compose and both `.env` files 0600; production
base Compose 0664 and Caddy Compose 0600; scripts 0700. Files have UID/GID
1001:1001. A dedicated runtime configuration read GID has NOT been allocated.

## Why configuration does not arrive

1. The reviewed GitHub image deployment workflows pass the deployment SHA, not
   integration credentials, to the scripts.
2. Neither supplied script receives or installs a configuration snapshot.
3. Their `compose()` wrappers specify no runtime overlay.
4. Both scripts exit early when the running image equals the requested image.
   Re-running CD on the same SHA therefore does not reload changed configuration.
5. Docker metadata reports the inspected provider environment names missing,
   no declared `SPRING_PROFILES_ACTIVE`, no additional config location, and no
   mount at `/run/aureliabooks-config`. This does not exclude every alternative
   Spring configuration source and does not establish provider validity.
6. The reviewed runtime sync workflow remains offline-only with its production
   sync job hard-blocked. No actual receiver/installer exists in this repository.

`BOOTSTRAP_ADMIN_ENABLED` was reported present, but its value and the actual
existence/password of an administrator were not inspected. R2 flags were not
explicitly declared in container environment. The inspected application defaults
disable R2 overall but default upload-enabled to true; future overlays must
explicitly set BOTH flags false.

## Integration requirements derived from the scripts

- Keep image deployment and configuration activation as distinct operations.
  Configuration activation must support the same image SHA without changing
  existing image-deploy no-op semantics.
- Make every deployment/rollback Compose invocation preserve the selected
  immutable runtime overlay. Resolve and validate it while holding the existing
  environment-specific deployment lock; include it after existing Compose files.
- Use the same host lock for configuration stage/activation/recovery as required
  by the transaction design. A GitHub concurrency group alone is insufficient.
- Do not put provider secrets into `.env` managed by image deployment. Existing
  image backups should not become provider credential archives.
- Keep database credentials, volumes, APP_IMAGE authority, SSH identity and proxy
  ownership intact. Only recreate `app` during configuration activation.
- Confirm image, generation label/mount and readiness after activation; HTTP `/`
  alone does not validate Google/Mail/VNPay credentials.
- Activate Google explicitly after reviewing existing profiles and callback/proxy
  behavior. Presence of credentials alone does not enable the google profile.
- Current script EXIT traps retain backups; they do not implement durable
  configuration transaction recovery or prove restart/power-loss atomicity.
- Quiet Compose validation may still emit diagnostic stderr. A future secret
  adapter must not forward raw parser, Docker, application or child-process
  failure messages into GitHub logs.

## Still unverified before a live adapter can be installed

- Sanitized structure of actual Compose services, environment/entrypoint/command,
  existing mounts, profile settings and proxy configuration; preserve any existing
  configuration sources and review higher-precedence conflicts.
- Restricted receiver/installer identity, fixed command authorization, dedicated
  read group, root-owned configuration files and cross-UID access tests.
- All manual/deploy/rollback entrypoints honoring the lock and overlay, durable
  recovery after process/SSH loss, and disposable rehearsal evidence.
- Staging and production GitHub Environment branch/reviewer contracts. A supplied
  screenshot shows one production approval, not all protection settings.
- Staging-specific provider credentials and callback ownership. Production
  provider/bucket selection must be reviewed separately.

No live adapter is approved by this document. No VPS change, provider call,
secret discovery, R2 enablement, commit, push or workflow execution was performed
while recording these observations. The next implementation target is a staging
adapter tested against disposable fixtures; live activation remains separate.

## Staging Compose supplied subsequently

The complete 70-line staging Compose source was supplied without credential
values. App uses APP_IMAGE, DB_HOST/PORT/NAME/USER/PASS, PORT=8080,
AVATAR_DIRECTORY=/app/uploads/avatars and BOOTSTRAP_ADMIN_ENABLED=false. It mounts
`./data/uploads:/app/uploads`, binds `127.0.0.1:18080:8080`, and joins backend.
MySQL uses a named data volume, two init SQL mounts and a healthcheck. Keep all
of those settings unchanged. Bootstrap false does not establish whether an admin
already exists. No app profile, additional config location or command/entrypoint
override is declared in this supplied Compose source.

`StagingConfigPlan.java` now prepares an overlay and a deployment
patch in memory only. It refuses production script input and unexpected/repeated
patches. The patch preserves the image no-op and existing Compose calls, resolves
one immutable runtime overlay under the existing lock, and uses it for subsequent
Compose calls including rollback. Google profile activation is a separate explicit
render option, false by default; no generic profile payload is accepted.

IMPORTANT: This patch is NOT installable yet. It requires an intentionally absent
privileged resolver at `/usr/local/libexec/aureliabooks-staging-runtime-resolve`;
absence or invalid response blocks image deployment before mutation. The resolver
must validate generation permissions/hashes, pending transaction state and the
active pointer while the caller holds deploy.lock. Only it may return NONE for a
verified installation without active runtime configuration. Receiver, provisioning,
configuration activation and restart recovery remain unimplemented/unverified.
Do not install the patch simply to try it. The read GID in tests is synthetic and
has not been approved/allocated on the VPS.

Historical validation before the Java migration: 60 Python tests passed (47
existing, 5 inventory, 8 staging preparation). Those files have since been replaced
by Java/JUnit; see CONFIG_SYNC_OFFLINE_RESULTS.json. Overlay checks use YAML parsing, not actual
Docker Compose merging or container recreation. bash syntax is checked on the
candidate patch. No new Spring behavior was implemented and the Java suite was
not repeated for this increment. These results do not establish live readiness.
