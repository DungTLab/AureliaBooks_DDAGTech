package com.ddagtech.aureliabooks.runtimeconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;
import static com.ddagtech.aureliabooks.runtimeconfig.RuntimeConfigFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigSnapshotStoreTest {
    @TempDir Path directory;
    private RuntimeSnapshotStore store() throws Exception {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Files.createDirectory(directory.resolve("releases"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        long uid = ((Number)Files.getAttribute(directory, "unix:uid")).longValue();
        return new RuntimeSnapshotStore(directory, uid, 999);
    }
    private void stage(RuntimeSnapshotStore store) { store.stage(FIRST, REVISION, values("google"), List.of("google"), "receiver"); }
    @Test void stageCreatesProtectedImmutableSnapshotButNoActivationPointer() throws Exception {
        var store = store(); stage(store);
        Path release = directory.resolve("releases/" + FIRST);
        assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(release));
        assertEquals(PosixFilePermissions.fromString("r--r-----"), Files.getPosixFilePermissions(release.resolve("runtime-integrations.properties")));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(release.resolve("metadata.properties")));
        assertFalse(Files.exists(directory.resolve("current")));
        String metadata = Files.readString(release.resolve("metadata.properties")); absent(SECRET, metadata);
        assertTrue(metadata.contains("status=STAGED_NOT_ACTIVE"));
        byte[] before = Files.readAllBytes(release.resolve("runtime-integrations.properties"));
        assertThrows(Rejected.class, () -> stage(store));
        assertTrue(Arrays.equals(before, Files.readAllBytes(release.resolve("runtime-integrations.properties"))), "GENERATION_OVERWRITTEN");
    }
    @Test void requestCannotEscapeRootOrActivateByRole() throws Exception {
        var store = store();
        assertThrows(Rejected.class, () -> store.stage("../escape", REVISION, values("google"), List.of("google"), "receiver"));
        assertThrows(Rejected.class, () -> store.stage(FIRST, REVISION, values("google"), List.of("google"), "installer"));
    }
    @Test void statusReconcilesUncertainTransferWithoutPrintingPayloadOrAllowingRevisionChange() throws Exception {
        var store = store(); assertEquals("MISSING", store.status(FIRST, REVISION));
        stage(store); assertEquals("STAGED_NOT_ACTIVE", store.status(FIRST, REVISION));
        assertThrows(Rejected.class, () -> store.status(FIRST, "a".repeat(40)));
        Path payload = directory.resolve("releases/" + FIRST + "/runtime-integrations.properties");
        Files.setPosixFilePermissions(payload, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(payload, "tampered");
        Files.setPosixFilePermissions(payload, PosixFilePermissions.fromString("r--r-----"));
        assertThrows(Rejected.class, () -> store.status(FIRST, REVISION));
    }
    @Test void unsafeRootAndLockFailClosed() throws Exception {
        var store = store(); Path lock = directory.resolve("stage.lock");
        Files.createFile(lock); Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-rw-rw-"));
        assertThrows(Rejected.class, () -> stage(store));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThrows(Rejected.class, () -> stage(store));
    }
    @Test void symlinkLockIsNeverFollowed() throws Exception {
        var store = store(); Path decoy = directory.resolve("decoy"); Files.writeString(decoy, "unchanged");
        Files.createSymbolicLink(directory.resolve("stage.lock"), decoy);
        assertThrows(Rejected.class, () -> stage(store)); assertEquals("unchanged", Files.readString(decoy));
    }
    @Test void concurrentStageCannotBypassTheStageLock() throws Exception {
        var store = store(); stage(store);
        try (var channel = FileChannel.open(directory.resolve("stage.lock"), StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertThrows(Rejected.class, () -> store.stage(SECOND, REVISION, values("google"), List.of("google"), "receiver"));
        }
    }
    @Test void environmentBindingPreventsProductionFrameBeingStagedAsStaging() throws Exception {
        var store = store(); Clock now = Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC);
        var frame = new StagingConfigEnvelope("production", FIRST, REVISION, 990, 1100, values("google"), List.of("google")).encode();
        assertThrows(Rejected.class, () -> StagingConfigEnvelope.receive(frame, "staging", REVISION, now, "receiver", store));
        assertEquals(FIRST, StagingConfigEnvelope.receive(frame, "production", REVISION, now, "receiver", store));
    }
}
