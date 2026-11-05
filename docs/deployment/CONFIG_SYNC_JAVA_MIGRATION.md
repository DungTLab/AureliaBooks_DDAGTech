# Configuration Sync: Java migration review notes

Historical migration checkpoint. Current implementation and operating limits are
in [RUNTIME_CONFIG_SYNC.md](RUNTIME_CONFIG_SYNC.md); earlier test counts and
patch-generation descriptions below are not the current release evidence.

Scope: local/offline migration approved in chat on 2026-11-03. Existing worktree
was clean for tracked files, with six untracked staging preparation files from
the same task. None of those unrelated-to-baseline additions were staged or lost:
the in-scope staging preparation sources/tests are now Java; the Bash inventory
and two staging documents remain. No commit/push is authorized by this change.

## Migration map

| Former Python component | Java replacement | Evidence |
|---|---|---|
| policy.py | ConfigPolicy | 25 exact EV/ES fields, complete snapshots, forbidden keys, both flags false, serializer and input limits |
| offline_store.py | OfflineConfigStore | permissions, private creation, immutable generations, rollback, exception recovery, shared flock |
| staging_plan.py | StagingConfigPlan | explicit Google decision, additive overlay, fail-closed staging deploy candidate |
| test_runtime_config.py | RuntimeConfigPolicyTest, RuntimeConfigStoreTest, RuntimeConfigRepositoryTest | policy/security/transaction/repository checks |
| test_staging_plan.py | RuntimeConfigStagingPlanTest | staging overlay, patch order, shell syntax and rejection cases |
| test_vps_inventory.py | RuntimeConfigInventoryTest | fake Docker markers, error suppression, target validation |
| Python serializer invoked by Boot tests | in-process ConfigPolicy.render | original 25 real Boot/child-I/O tests retained |
| Python synthetic children | RuntimeConfigurationSyntheticChild | Java deadline/pipe/flood/interruption/kill/reap checks |

Old Python tests sometimes combined several cases; JUnit uses different grouping
and parameterization. Counts are not a one-to-one proof of equivalence. The table,
actual assertions and historical source in Git are the review basis. Historical
review documents are retained under archive/, explicitly superseded.

## Behavioral differences requiring review

- The payload properties wire format remains sorted deterministic ASCII; values
  round-trip through Properties and real Boot. The Java URI parser rejects some
  ambiguous inputs more strictly. No credentials are normalized or trimmed.
- Disposable fixture metadata uses private properties instead of JSON. There is
  no live receiver/deployed generation to migrate. This change must not be used
  as an automatic migration of real server data.
- The shared lock still uses Linux flock via a bounded static shell lease. Java
  FileLock is not a substitute for the existing deploy script's flock primitive.
- The standalone JAR contains only the runtimeconfig package and depends solely
  on java.base. It has no Spring bootstrap, datasource, provider client or live
  entrypoint. The main application packaging still selects AureliaBooksApplication.
- Bash remains for Docker/SSH/OS integration. Existing deployment scripts on the
  VPS retain their embedded Python; replacing those scripts was explicitly excluded.

## Final offline evidence

99 configuration-sync JUnit cases pass under both umask 077 and 000. A separate
22-case R2 MVC/context/security/defaults regression passes with the cached Mockito
agent supplied on the test command line. Without that agent the first regression
attempt hit the environment's self-attach restriction; no application change was
made to work around it. Distinct final cases: 121; repeated runs are not added.

Offline Spring Boot packaging succeeds, and its Start-Class remains the existing
application. The separate tool JAR packages successfully, jdeps reports java.base,
and its main exits 2 with the expected static BLOCKED diagnostic. JUnit verifies
the thirteen original application/deploy baseline hashes and workflow/YAML guards.
No synthetic-secret prefix was found in the configuration-sync Surefire XML/text
reports. actionlint is unavailable; GitHub CI/full application E2E are not claimed.

No live performance equivalence is claimed. In-process serialization removes the
test-time interpreter subprocess, but host JVM startup, memory and actual receiver
throughput still need measurement before installing a real adapter.

## Remaining release gates

New independent review must assess the Java implementation; the old Python review
does not carry forward. Actual receiver/installer/resolver, root/cross-UID access,
process-restart recovery and power-loss durability remain unproven. Production
sync stays hard-blocked, R2 stays false, and configuration has not been activated.
The original staging/production missing-configuration issue is therefore not yet
fixed on the VPS by this language migration.
