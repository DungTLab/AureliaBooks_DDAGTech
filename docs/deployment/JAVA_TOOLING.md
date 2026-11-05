# Java maintenance tools and CI gates

All executable Python sources and inline Python calls in this repository have
been replaced by Java 21. This is an implementation migration, not a GitHub
language-statistics override; no Linguist exclusions are added. Bash still invokes
SSH, Docker, Maven and the JDK. The application remains a layered monolith.

## Commands from repository root

```bash
bash scripts/java-tools.sh CatalogSeed --policy
bash scripts/java-tools.sh CatalogSeed --dry-run --environment staging --ledger /path/to/reviewed-ledger.json
bash scripts/java-tools.sh R2QaTools proxy-start
bash scripts/java-tools.sh R2QaTools http
bash scripts/java-tools.sh R2QaTools proxy-stop
bash scripts/java-tools.sh R2QaTools all
```

The launcher compiles the JDK-only tooling package into an exclusive temporary
directory, executes Java, and removes the classes. A Java 21 **JDK** is required
on the development machine and GitHub runner. The VPS runtime receiver continues
to use its prebuilt JAR and needs only a Java 21 JRE.

CatalogSeed preserves eight-file inventory, INSERT-only static checks, masking
of quoted strings/comments, checksums, ledger validation, review-required states
and dry-run-only behavior. It has no database driver or SQL execution path.
The previous Python tests are replaced by JUnit fixture/parameterized cases.

WorkflowGate preserves main-HEAD checks, CI polling, matching SHA/event/branch,
run-attempt selection, required successful staging steps and fail-closed errors.
GitHub calls use the runner's gh CLI with bounded subprocess I/O. The automatic
staging and promotion jobs now checkout trusted main sources and set up Java 21
before validation. SSH commands, Environment bindings and concurrency groups are
unchanged. Tests invoke a synthetic gh executable, not the GitHub API.

R2QaTools preserves the fixed local QA database/user/password-file guard, loopback
URLs, pinned read-only Nginx, proxy limits, explicit stop and cleanup checks. It
adds a container ownership label, rejects URL credentials/query/fragment, and
bounds the HTTP test subprocess. The 28 HTTP scenarios are now the opt-in JUnit
R2HttpEndToEndTest. They remain disabled in default CI; the Java live matrix has
not been run against MySQL/Nginx during this migration. See scripts/r2-e2e/README.md.

Historical Python results under docs/deployment/archive/ describe older code and
are not certification of the Java replacement. Tools already installed directly
on the VPS, including the legacy image deploy scripts, are outside this Git tree
and have not been changed by this migration.

The runtime configuration workflow stages an inactive snapshot only. This
migration does not implement activation/restart/rollback on the running VPS, does
not enable R2, and does not implicitly select Google or sms-demo Spring profiles.

## Verification

```bash
./mvnw -B -ntp verify
```

CatalogSeedTest, JavaToolsTest and WorkflowGateCliTest cover static SQL/ledger
rules, malformed/bounded JSON, synthetic credential handling, loopback guards,
bounded child processes, workflow syntax and fake-GitHub release gates. Runtime
configuration security/escaping/rollback fixture suites remain in the same build.
External/MySQL QA tests require their existing explicit opt-in properties.
