# Runtime configuration synchronization and activation

The manual workflow supports validate (default), stage, and activate-google.
Validation has no Environment secret bindings. Stage/activation select the named
GitHub Environment, require main and its current SHA, and retain Environment
branch/reviewer protections. Provider credentials are never printed or uploaded.

## Flow

1. The JDK-only sender reads the explicit allowlist and validates complete groups.
2. A bounded environment/SHA/expiry-bound frame goes through a private SSH pipe.
3. Root receiver stages a protected immutable generation. R2 defaults to disabled.
4. Only activate-google recreates app with a read-only configuration mount, the
   explicit google profile, framework forwarded-header handling and the approved R2 flags.
5. It checks unchanged image, mount/label/GID/readability, loaded R2 flags, HTTP readiness, enabled
   Google button and HTTPS OAuth callback URL. It does not call the provider or
   complete a login, send test email, or create a payment transaction.
6. On activation failure, the previous overlay is restored and app recreated. A
   failed recovery leaves a private journal that blocks later deployment.

An authorized operator can activate a staged
generation using the installed fixed receiver command below. Do not select a
feature branch to bypass main/Environment restrictions.

## Explicit EV/ES bindings

| Source | Scope | Destination |
|---|---|---|
| VPS_HOST, VPS_USER, VPS_SSH_KEY, VPS_SSH_HOST_KEY | ES | runner SSH only |
| GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET | ES | Google aliases in properties |
| MAIL_USERNAME, MAIL_PASSWORD | ES | Spring mail |
| VNPAY_TMN_CODE, VNPAY_HASH_SECRET | ES | canonical vnpay properties |
| MAIL_HOST, MAIL_PORT, MAIL_AUTH, MAIL_STARTTLS, MAIL_FROM | EV | Spring mail/contact mail-from |
| DEMO_SMS_INBOX | EV | demo.sms.inbox, without enabling sms-demo |
| VNPAY_PAY_URL, VNPAY_API_URL, VNPAY_RETURN_URL, VNPAY_VERSION, VNPAY_COMMAND, VNPAY_ORDER_TYPE, VNPAY_EXPIRE_MINUTES | EV | canonical vnpay properties |
| R2_ENABLED, R2_UPLOAD_ENABLED | EV, default false | environment and properties |
| R2_ENDPOINT, R2_BUCKET, R2_UPLOAD_REQUESTS_PER_MINUTE, R2_UPLOAD_MAX_CONCURRENT | EV | app.r2 properties when enabled |
| R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY | ES | app.r2 properties in the protected snapshot when enabled |

DB credentials, APP_IMAGE, arbitrary imports, JVM
options, bootstrap settings or arbitrary profile names are accepted in payloads.
Activation rejects conflicting provider environment variables, profile overrides,
JVM arguments and config imports. VNPay return URL must match the selected
environment's HTTPS /payment/vnpay-return URL. SMTP requires auth and STARTTLS.
The explicitly approved google profile does not implicitly enable sms-demo.

## Product image upload rollout

Before dispatching from main, install the reviewed JAR version supporting R2 on
the VPS. The existing wrapper and sudoers command list are sufficient. Keep the
prior JAR available before rollout. An older JAR cannot read an R2-enabled
generation. Activate a compatible R2-disabled generation with the new tool
before reverting to an older tool.

1. Use private, separate buckets. This deployment policy accepts
   `aureliabooks-staging-assets` for staging and
   `aureliabooks-production-assets` for production. Confirm the bucket names
   and the account's default-jurisdiction S3 endpoint before enabling.
2. Use separate Cloudflare Object Read & Write credentials scoped to each
   bucket. Set `R2_ENDPOINT`, `R2_BUCKET`, `R2_ENABLED=true` and
   `R2_UPLOAD_ENABLED=true` in the staging GitHub Environment, and set both
   R2 secrets there. Run `activate-google` for staging from main. This
   operation activates the selected snapshot, including the explicit R2 flags.
3. Verify with a MANAGER using a test Book or Stationery. Upload a small
   JPEG/PNG, save the product, then verify that its image loads in the storefront
   when the product is active. Readiness during activation verifies startup and
   flag delivery. This browser check verifies the bucket and credentials.
4. Repeat for production through its Environment review after staging passes.

To pause uploads after products already use R2 images, apply a new snapshot with
`R2_ENABLED=true` and `R2_UPLOAD_ENABLED=false`. Keep the existing R2 endpoint,
bucket and credentials available so that the storefront can still read the
saved images. Turning off R2 itself also disables serving those images.

## Protected state

```text
/var/lib/aureliabooks-runtime/<environment>/      root:root 0700
  releases/                                    root:root 0700
    <generation>/                              root:999 0750
      runtime-integrations.properties          root:999 0440
      metadata.properties                      root 0600
  activation.pending                           root 0600; while transaction pending
  deploy-before-runtime.sh                     root 0600; original host script
/etc/aureliabooks-runtime/<environment>/         root:root 0755
  active.compose.yaml                          root:root 0644; NO secrets
```

The public overlay is the single selected configuration file; replacement uses
same-filesystem atomic rename and directory fsync. The journal is written and
fsynced before overlay replacement. Snapshot hash/permissions are checked before
activation and deployment. Temporary files begin private independently of umask.
Controller interruption recovery is fixture-tested; actual host power-loss
guarantees and a restore rehearsal are not claimed.

## Fixed receiver operations

Installed wrapper: /usr/local/sbin/aureliabooks-runtime-stage. Tool JAR:
/usr/local/lib/aureliabooks-runtime/runtime-config-tool.jar, root-owned 0644 under
a root-owned 0755 directory. Host needs Java 21 JRE. The wrapper clears JVM
environment and accepts only fixed operations, environments, SHA/ID formats.
Receiver paths and app GID 999 are fixed server-side contracts.

```bash
sudo -n /usr/local/sbin/aureliabooks-runtime-stage check staging FULL_SHA
sudo -n /usr/local/sbin/aureliabooks-runtime-stage status staging FULL_SHA GENERATION
sudo -n /usr/local/sbin/aureliabooks-runtime-stage verify-active staging FULL_SHA
sudo -n /usr/local/sbin/aureliabooks-runtime-stage activate-google staging FULL_SHA GENERATION
sudo -n /usr/local/sbin/aureliabooks-runtime-stage recover staging FULL_SHA
```

Replace FULL_SHA with the snapshot source SHA (40 lowercase hex characters), and
GENERATION with its 32-character receipt. Repeat with production only under its
authorization. Status is immutable snapshot storage state; verify-active reports
the selected generation. Reconcile uncertain operations before retrying.

Activation/recovery/integrate-deploy hold the existing host deploy.lock via flock.
verify-active does not relock because image deployment already holds the lock.
Stage writers use a separate lock and cannot overwrite published generations.
Docker operations use fixed project/file/service arguments, --no-deps and
--pull never. Image and DB stay unchanged during config activation. Child errors,
HTTP/provider responses and causes are never emitted. I/O/probes are bounded.

## Keeping configuration across image deployment

RuntimeDeployIntegration accepts only the two audited host-script hashes and
backs up their originals. integrate-deploy modifies only compose(): validation,
normal recreation and rollback include the protected overlay when present.
Unexpected source/manual changes fail closed. Legacy APP_IMAGE update, image
selection and image rollback behavior are retained. Integration alone does not
activate a snapshot.

Provisioning requires explicit approval for root installation, protected state,
narrow sudoers and script integration. Grant only the fixed wrapper, listing each
operation and environment; validate with visudo. Do not grant arbitrary shell,
Java or Docker. The preserved deployment identity already controls Docker and is
not claimed to be isolated from root. Operator evidence stays outside source.

## Remaining external checks

Google Console must authorize each environment callback URL. Rendering the button
and validating its redirect do not certify a full OAuth exchange. Loading config
does not prove SMTP/VNPay credential validity. Revoked old credentials may make
rollback unusable. Operators manage backups, core dumps/swap, disk access and
snapshot retention. Root-host compromise is outside this protocol's boundary.

Historical stage-only/Python review receipts are under archive/. Their PASS does
not certify new activation code. Java tests use synthetic credentials only.
