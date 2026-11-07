package com.ddagtech.aureliabooks.runtimeconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;
import static com.ddagtech.aureliabooks.runtimeconfig.RuntimeConfigFixtures.*;
import static com.ddagtech.aureliabooks.runtimeconfig.OfflineConfigStore.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigStoreTest {
    private static final String IMAGE_B = "sha256:" + "b".repeat(64);
    private void stage(OfflineConfigStore store, String generation) { store.stage(generation, REVISION, values("google"), List.of("google"), "receiver"); }
    private void activate(OfflineConfigStore store, String generation, String old) { store.activate(generation, old, IMAGE_A, "installer", null); }
    private void first(OfflineConfigStore store) { stage(store, FIRST); activate(store, FIRST, null); }
    private void permissions(Path path, String mode) throws Exception {
        assertEquals(PosixFilePermissions.fromString(mode), Files.getPosixFilePermissions(path));
    }
    @Test void stagingNeverActivatesAndGenerationsAreImmutable() {
        try (var store = new OfflineConfigStore()) {
            stage(store, FIRST); assertNull(store.confirmed()); assertNull(store.app.generation);
            assertThrows(Rejected.class, () -> stage(store, FIRST));
        }
    }
    @Test void activationPreservesImageDatabaseAndOverlay() {
        try (var store = new OfflineConfigStore()) {
            first(store); assertEquals(FIRST, store.confirmed()); assertEquals(IMAGE_A, store.app.image);
            assertEquals("synthetic-database-identity", store.app.database);
            assertEquals(store.validate(FIRST), store.app.overlay);
        }
    }
    @Test void failedReadinessRestoresPreviousGeneration() {
        try (var store = new OfflineConfigStore()) {
            first(store); stage(store, SECOND); store.app.failNext = true;
            assertThrows(Rejected.class, () -> activate(store, SECOND, FIRST));
            assertEquals(FIRST, store.confirmed()); assertEquals(FIRST, store.app.generation);
            assertEquals("ROLLED_BACK", store.status());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"before_journal", "after_journal", "after_pointer", "after_recreate", "before_confirm", "after_confirm", "after_commit"})
    void exceptionInjectionRecoveryAtEveryBoundary(String point) {
        try (var store = new OfflineConfigStore()) {
            first(store); stage(store, SECOND);
            assertThrows(SimulatedCrash.class, () -> store.activate(SECOND, FIRST, IMAGE_A, "installer", point));
            store.recover("installer");
            String expected = point.equals("after_commit") ? SECOND : FIRST;
            assertEquals(expected, store.confirmed()); assertEquals(expected, store.app.generation);
        }
    }
    @Test void pendingTransactionBlocksDeployment() {
        try (var store = new OfflineConfigStore()) {
            stage(store, FIRST);
            assertThrows(SimulatedCrash.class, () -> store.activate(FIRST, null, IMAGE_A, "installer", "after_pointer"));
            assertThrows(Rejected.class, () -> store.deploy(IMAGE_B, null, "installer"));
        }
    }
    @Test void fsyncFailureBeforeJournalPromotionDoesNotActivate() throws Exception {
        try (var store = new OfflineConfigStore()) {
            stage(store, FIRST);
            store.hook = point -> { if (point.equals("before_fsync_transaction")) throw new Rejected("SYNTHETIC_DISK_FAILURE"); };
            assertThrows(Rejected.class, () -> activate(store, FIRST, null));
            assertNull(store.confirmed()); assertNull(store.app.generation);
            try (var files = Files.list(store.root)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".pending-"))); }
        }
    }
    @Test void failedConfirmationRollsBackWithoutDisclosingCause() {
        try (var store = new OfflineConfigStore()) {
            stage(store, FIRST); AtomicBoolean once = new AtomicBoolean();
            store.hook = point -> { if (point.equals("before_write_confirmed") && !once.getAndSet(true)) throw new IllegalStateException(SECRET); };
            var error = assertThrows(Rejected.class, () -> activate(store, FIRST, null));
            absent(SECRET, report(error)); assertNull(store.confirmed()); assertNull(store.app.generation);
        }
    }
    @Test void failedRollbackBlocksOperationsUntilRecovery() {
        try (var store = new OfflineConfigStore()) {
            stage(store, FIRST); store.app.failAll = true;
            assertThrows(Rejected.class, () -> activate(store, FIRST, null));
            assertEquals("RECOVERY_FAILED", store.status());
            assertThrows(Rejected.class, () -> store.deploy(IMAGE_B, null, "installer"));
            store.app.failAll = false; store.recover("installer"); assertNull(store.confirmed());
        }
    }
    @Test void staleApprovalImageConflictAndReplayRejected() {
        try (var store = new OfflineConfigStore()) {
            stage(store, FIRST);
            assertThrows(Rejected.class, () -> activate(store, FIRST, SECOND));
            assertThrows(Rejected.class, () -> store.activate(FIRST, null, IMAGE_B, "installer", null));
            activate(store, FIRST, null);
            assertThrows(Rejected.class, () -> activate(store, FIRST, FIRST));
        }
    }
    @Test void providerRemovalRequiresExplicitHistoricalRollback() {
        try (var store = new OfflineConfigStore()) {
            first(store);
            Map<String, String> combined = new HashMap<>(values("google")); combined.putAll(values("mail"));
            store.stage(SECOND, REVISION, combined, List.of("google", "mail"), "receiver");
            activate(store, SECOND, FIRST);
            assertThrows(Rejected.class, () -> activate(store, FIRST, SECOND));
            store.deploy(IMAGE_B, SECOND, "installer");
            store.rollback(FIRST, SECOND, IMAGE_B, "installer");
            assertEquals(FIRST, store.confirmed()); assertEquals(IMAGE_B, store.app.image);
        }
    }
    @Test void unconfirmedRollbackTargetAndReceiverActivationDenied() {
        try (var store = new OfflineConfigStore()) {
            first(store); stage(store, SECOND);
            assertThrows(Rejected.class, () -> store.rollback(SECOND, FIRST, IMAGE_A, "installer"));
            assertThrows(Rejected.class, () -> store.rollback(FIRST, FIRST, IMAGE_A, "receiver"));
            assertThrows(Rejected.class, () -> store.activate(SECOND, FIRST, IMAGE_A, "receiver", null));
            assertThrows(Rejected.class, () -> store.recover("receiver"));
            assertThrows(Rejected.class, () -> store.stage("3".repeat(32), REVISION, values("google"), List.of("google"), "application"));
        }
    }
    @Test void permissionsAndTamperedGenerationRejected() throws Exception {
        try (var store = new OfflineConfigStore()) {
            stage(store, FIRST);
            Path path = store.root.resolve("releases/" + FIRST + "/runtime-integrations.properties");
            permissions(path, "r--r-----"); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            assertThrows(Rejected.class, () -> activate(store, FIRST, null));
            Files.writeString(path, "tampered"); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--r-----"));
            assertThrows(Rejected.class, () -> activate(store, FIRST, null));
        }
    }
    @Test void allModesNormalizedAndFullLifecycleWorks() throws Exception {
        // Entire suite is also launched under umask 077 and 000 externally.
        try (var store = new OfflineConfigStore()) {
            first(store); stage(store, SECOND); store.app.failNext = true;
            assertThrows(Rejected.class, () -> activate(store, SECOND, FIRST));
            assertThrows(SimulatedCrash.class, () -> store.activate(SECOND, FIRST, IMAGE_A, "installer", "after_pointer"));
            store.recover("installer"); activate(store, SECOND, FIRST);
            store.rollback(FIRST, SECOND, IMAGE_A, "installer");
            permissions(store.root, "rwx------"); permissions(store.root.resolve("releases"), "rwx------");
            for (String generation : List.of(FIRST, SECOND)) {
                Path release = store.root.resolve("releases/" + generation);
                permissions(release, "rwxr-x---"); permissions(release.resolve("metadata.properties"), "rw-------");
                permissions(release.resolve("runtime-integrations.properties"), "r--r-----"); permissions(release.resolve("compose.runtime.yml"), "r--r-----");
            }
            for (String name : List.of("confirmed.properties", "current.properties", "transaction.properties", "history.properties", "host.lock"))
                permissions(store.root.resolve(name), "rw-------");
        }
    }
    @Test void filesPrivateUntilGenerationPromotion() {
        try (var store = new OfflineConfigStore()) {
            AtomicBoolean observed = new AtomicBoolean();
            store.hook = point -> {
                try {
                    if (point.equals("created_private_file") || point.equals("before_release_grant")) {
                        permissions(store.root.resolve("releases/" + FIRST), "rwx------");
                        if (point.equals("created_private_file")) {
                            try (var paths = Files.list(store.root.resolve("releases/" + FIRST))) {
                                assertTrue(paths.anyMatch(p -> {
                                    try { return Files.getPosixFilePermissions(p).equals(PosixFilePermissions.fromString("rw-------")); }
                                    catch (Exception e) { throw new AssertionError("PERMISSION_QUERY_FAILED"); }
                                }));
                            }
                        }
                        observed.set(true);
                    }
                    if (point.startsWith("before_promote_")) {
                        try (var paths = Files.list(store.root)) {
                            for (Path p : paths.filter(p -> p.getFileName().toString().startsWith(".pending-")).toList()) permissions(p, "rw-------");
                        }
                    }
                } catch (Exception e) { throw new AssertionError("PRIVATE_CREATION_CHECK_FAILED"); }
            };
            stage(store, FIRST); activate(store, FIRST, null); assertTrue(observed.get());
        }
    }
    @Test void existingUnsafeLockIsNotRepaired() throws Exception {
        try (var store = new OfflineConfigStore()) {
            stage(store, FIRST); Path lock = store.root.resolve("host.lock");
            Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-rw-rw-"));
            assertThrows(Rejected.class, () -> stage(store, SECOND)); permissions(lock, "rw-rw-rw-");
        }
    }
    @Test void symlinksUnexpectedFilesAndReleaseSymlinksRejected() throws Exception {
        try (var store = new OfflineConfigStore()) {
            stage(store, FIRST); Path release = store.root.resolve("releases/" + FIRST);
            Files.createFile(release.resolve("unexpected")); assertThrows(Rejected.class, () -> store.validate(FIRST));
            Files.delete(release.resolve("unexpected"));
            Files.delete(release.resolve("runtime-integrations.properties"));
            Files.createSymbolicLink(release.resolve("runtime-integrations.properties"), store.root.resolve("confirmed.properties"));
            assertThrows(Rejected.class, () -> store.validate(FIRST));
            Files.createSymbolicLink(store.root.resolve("releases/" + SECOND), release);
            assertThrows(Rejected.class, () -> store.validate(SECOND));
        }
    }
    @Test void databaseDriftIsBlocked() {
        try (var store = new OfflineConfigStore()) {
            store.app.database = "different"; assertThrows(Rejected.class, () -> stage(store, FIRST));
        }
    }
    @Test void subsequentImageDeploymentKeepsOverlayAndFailureRestoresImage() {
        try (var store = new OfflineConfigStore()) {
            first(store); String overlay = store.app.overlay;
            store.app.failNext = true;
            assertThrows(Rejected.class, () -> store.deploy(IMAGE_B, FIRST, "installer"));
            assertEquals(IMAGE_A, store.app.image);
            store.deploy(IMAGE_B, FIRST, "installer");
            assertEquals(overlay, store.app.overlay); assertEquals(FIRST, store.confirmed());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"config", "image"}) @Timeout(15)
    void actualFlockBlocksConfigAndImageOperations(String operation) throws Exception {
        try (var store = new OfflineConfigStore(); var executor = Executors.newSingleThreadExecutor()) {
            first(store); stage(store, SECOND);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            store.app.beforeRecreate = point -> {
                entered.countDown();
                try { check(release.await(5, TimeUnit.SECONDS), "TEST_HOLD_TIMEOUT"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Rejected("TEST_INTERRUPTED"); }
            };
            Future<?> holder = executor.submit(() -> {
                if (operation.equals("config")) activate(store, SECOND, FIRST);
                else store.deploy(IMAGE_B, FIRST, "installer");
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var error = assertThrows(Rejected.class, () -> {
                    if (operation.equals("config")) store.deploy(IMAGE_B, FIRST, "installer"); else activate(store, SECOND, FIRST);
                });
                assertTrue("BUSY_SHARED_DEPLOYMENT_LOCK".equals(error.getMessage()), "UNEXPECTED_LOCK_ERROR");
            } finally { release.countDown(); }
            holder.get(5, TimeUnit.SECONDS);
        }
    }
    @Test void noSecretsInMetadataOrFailureDiagnostics() throws Exception {
        Path root;
        try (var store = new OfflineConfigStore()) {
            root = store.root; first(store);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    if (!path.getFileName().toString().equals("runtime-integrations.properties")) absent(SECRET, Files.readString(path));
                }
            }
            var error = assertThrows(Rejected.class, () -> activate(store, FIRST, FIRST)); absent(SECRET, report(error));
        }
        assertFalse(Files.exists(root));
    }
}
