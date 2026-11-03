# R2.37.3 — independent smoke protocol and offline preparation

Status: **offline harness prepared; live execution remains BLOCKED**. This is not R2.37.3B PASS. No Spring application, datasource, server, MySQL runner, bucket listing or credential discovery is part of this harness. All executable tests use synthetic identities, local image bytes and an in-memory SDK HTTP transport. The harness resides only in `src/test`, outside the production artifact.

## Prepare a concrete review draft (offline)

From a clean, committed checkout:

```bash
bash scripts/prepare-r2-smoke-offline.sh
```

Requires Java 21 and the existing Maven cache (`-o`); no new dependencies. It runs only `R2SmokeDraftPreparationTest`, with headless ImageIO, and writes a new `target/r2-smoke-draft/` directory:

- `approval-draft.json`: source revision, one UUID v4, exact objectKey, normalized PNG SHA-256 and length. Account/endpoint/jurisdiction and external attestations remain unconfirmed; executionApproved=false, approvedDeleteKey empty, approvalExpiresAt null. This draft cannot run.
- `source.jpg`: tiny synthetic 16×12 fixture, no user data.
- `normalized.png`: real ProductImageProcessor output, the expected PUT bytes. The JPEG hash is deliberately different.

Existing output directories are refused. Keep the generated ID; do not regenerate a replacement key after any live attempt. Preparation alone never constructs an S3Client, reads R2 credentials or opens a connection. The opt-in writer being PASS means only that local draft creation worked.

## Evidence required before any live runner is enabled

An operator must review the exact source revision and draft, provide redacted evidence and explicitly authorize the exact GET/PUT/DELETE scope. Do not paste credentials, SDK Authorization headers or unredacted errors into Git/chat/logs.

| Gate | Required external evidence |
|---|---|
| Account/endpoint/jurisdiction | Owning account ID, exact HTTPS endpoint and bucket jurisdiction. Current harness supports only DEFAULT with `https://<32 lowercase hex account ID>.r2.cloudflarestorage.com`; other jurisdictions require a separate policy review. No ports, paths, userinfo, query, fragment or trailing slash. |
| Bucket | Exactly `aureliabooks-staging-assets`; confirm it exists in that account, remains private, public r2.dev/custom-domain exposure is disabled, and it is not an application/Production data bucket. The historical name is not proof. |
| Lifecycle/concurrency | Record retention/lifecycle/locks and other writers. Establish an exclusive window for this exact key with no application references, writer, copy or lifecycle action. Do not change bucket policy from the harness. |
| Token | Object Read & Write scoped only to that bucket; non-Admin, current/not revoked, expiry checked. Record policy/status as redacted metadata. |
| Secrets | Named owner, approved secret source and process injection, access and rotation procedure. Only variable names/reference metadata in reports. No filesystem scan or fallback credential chain. |
| Approval | Exact revision, account/endpoint/bucket, UUID/key, normalized PNG SHA-256/length, explicit DELETE of that same key, short approval expiry and approving operator. Changing any field requires review again. |

Boolean attestations are manual gates, not independent proof that Cloudflare settings were verified. Keep the evidence alongside the approved manifest. Never turn all flags on as a convenience. A draft, offline PASS or previous QA approval does not authorize live calls.

There is intentionally **no live CLI, environment loader or automatic execution after approval** in this change. A future reviewed live entry point must load this exact approved artifact/fixture, check HEAD against sourceRevision and expiry, use the protocol's sdkBuilder with injected static credentials and a fixed journal path in the approved evidence directory. It must not generate IDs, copy a used approval to a new directory, rename/remove a journal, silently retry a run or launch the R2.37.2 QA runner. That entry point and actual bucket/token evidence still need separate authorization/review before R2.37.3B.

## Protocol and bounded operations

`R2SmokeProtocol.run()` validates the plan and re-normalizes the input before client creation. It exclusively creates a JSONL journal, fsyncs its directory entry and forces the complete plan before creating the client; every operation is journaled and forced before delegate I/O. Existing journals stop execution. If the process crashes, retain journal, exact key and fixture for operator reconciliation. No automatic resume is implemented.

1. GET only the exact approved bucket/key. An existing stream is aborted without downloading it. Proceed only on explicit S3 `404 NoSuchKey`; generic 404, NoSuchBucket, permission or transport errors stop execution.
2. Call **R2ProductImageStorage.store()**, using the real ProductImageProcessor and a package-private, one-use UUID supplier. The public Spring constructor still uses UUID.randomUUID(). Before PUT, the guard validates the actual RequestBody SHA-256/length/type against the approved normalized PNG.
3. The smoke-only wrapper adds `If-None-Match: *`, `smoke-run=<approved UUID>` and `smoke-sha256=<PNG digest>`. It permits one logical PUT. A 4xx rejection stops without claiming ownership or deleting even a matching-marker object. An unknown/5xx outcome can be inspected only at the same key; no new key or repeated PUT.
4. GET through **R2ProductImageStorage.read()**. Require image/png, exact length, full PNG SHA-256 and both ownership metadata fields. ETag is not a SHA-256 digest. A missing/corrupt/foreign object leaves OWNERSHIP_UNVERIFIED; in particular, 404 after PUT timeout is not cleanup proof because an in-flight PUT can still commit.
5. Re-read and verify the same ownership/content immediately before cleanup. DELETE only the explicitly approved exact bucket/key. This uses an unconditional S3 DeleteObject; the confirmed exclusive window is mandatory because a read followed by delete cannot eliminate a concurrent replacement race. Do not claim conditional-delete support based on AWS SDK fields alone.
6. GET the exact key again. Only explicit NoSuchKey verifies absence. An acknowledged DELETE alone, retained object, 403 or ambiguous read is CLEANUP_UNVERIFIED. A PUT error followed by proven-owned cleanup is PUT_UNCERTAIN_CLEANED, never PASS; a DELETE error followed by observed absence is DELETE_UNCERTAIN_ABSENT, never PASS.

Maximum: **one PUT, one DELETE, four GETs**; fewer on failures. No HEAD/list/scan/bulk/wildcard/copy/multipart operations, alternate overloads, automatic cleanup or production DELETE API. Missing ownership or unavailable evidence storage stops writes. If the exclusivity promise becomes untrue, stop; preserve exact-key evidence for separately approved reconciliation rather than broad cleanup.

SDK v2 remains pinned by pom.xml (2.57.8). sdkBuilder matches production's static credentials, region auto, path style, chunkedEncodingEnabled=false and call/attempt timeouts 30s/10s. Its deliberate difference is RetryPolicy.none(): **one HTTP attempt per operation**, including timeout/500. Client endpoint/region/static provider/timeouts/retry policy are checked before the first operation. The real SDK serializes/signs requests in the offline tests; FakeHttp cannot perform DNS/socket I/O. No live compatibility claim follows from this.

## Offline verification

```bash
./mvnw -o -B -ntp -Djava.awt.headless=true -Dtest=R2SmokeProtocolTest test
```

The parameterized matrix covers normal success; existing keys; 403/NoSuchBucket/generic-404 preflight; timeout before/after object creation and simulated late commit; 412 (including a matching but foreign marker); 500 before/after commit; wrong ownership/hash/mime/length; replacement before DELETE; DELETE returning success but retaining the object; ambiguous DELETE outcomes; and failed absence verification. Further tests check all approval gates, exact endpoint syntax, fixture digest, SDK policy before requests, operation/bucket/key/body/count allowlists, expired approval, failed journal persistence, no overwrite, invalid injected UUID and Spring constructor selection without an application/datasource. Journal tests reject raw SDK-message/credential/header sentinels.

Cloudflare documents atomic conditional PUT and the limitation of negative reads after unknown writes in [S3 extensions](https://developers.cloudflare.com/r2/api/s3/extensions/#conditional-operations-in-putobject). See [S3 compatibility](https://developers.cloudflare.com/r2/api/s3/api/) for the current operation matrix. These sources support protocol design, not the status of this project's bucket/token. Production lifecycle policy remains in [R2_PRODUCT_IMAGES.md](R2_PRODUCT_IMAGES.md).
