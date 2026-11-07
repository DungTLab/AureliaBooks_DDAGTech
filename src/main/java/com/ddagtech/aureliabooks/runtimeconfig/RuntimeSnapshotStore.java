package com.ddagtech.aureliabooks.runtimeconfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Protected immutable staging area only. Never activates or executes Docker. */
public final class RuntimeSnapshotStore implements StagingConfigEnvelope.StageSink {
    private final Path root;
    private final long owner;
    private final int appGid;

    // Test-only construction can use a disposable fixture; CLI never accepts a path/UID/GID.
    RuntimeSnapshotStore(Path root, long owner, int appGid) {
        this.root = root; this.owner = owner; this.appGid = appGid;
        check(appGid > 0, "INVALID_APP_GID");
        inspectDirectory(root, 0700); inspectDirectory(root.resolve("releases"), 0700);
    }
    public static RuntimeSnapshotStore deployed(String environment) {
        check(Set.of("staging", "production").contains(environment), "INVALID_TARGET_ENVIRONMENT");
        // Server-local root-owned contracts, never caller-selected roots or truthy JSON flags.
        for (Path parent : List.of(Path.of("/var"), Path.of("/var/lib"), Path.of("/var/lib/aureliabooks-runtime"))) {
            try {
                check(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)
                        && ((Number)Files.getAttribute(parent, "unix:uid", NOFOLLOW_LINKS)).longValue() == 0
                        && (((Number)Files.getAttribute(parent, "unix:mode", NOFOLLOW_LINKS)).intValue() & 0022) == 0,
                        "UNSAFE_RUNTIME_PARENT");
            } catch (IOException e) { throw new Rejected("RUNTIME_NOT_PROVISIONED"); }
        }
        // Existing app containers were reported UID/GID 999:999. Provisioning must verify again.
        return new RuntimeSnapshotStore(Path.of("/var/lib/aureliabooks-runtime", environment), 0, 999);
    }
    private void inspectDirectory(Path path, int permissions) {
        try {
            check(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                    && ((Number)Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS)).longValue() == owner
                    && (((Number)Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS)).intValue() & 07777) == permissions,
                    "UNSAFE_RUNTIME_DIRECTORY");
        } catch (IOException e) { throw new Rejected("RUNTIME_NOT_PROVISIONED"); }
    }
    private static java.nio.file.attribute.FileAttribute<?> privateMode() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    }
    private static void directorySync(Path path) throws IOException {
        try (var channel = FileChannel.open(path, READ, NOFOLLOW_LINKS)) { channel.force(true); }
    }
    private void file(Path path, byte[] bytes, int mode) throws IOException {
        try (var channel = FileChannel.open(path, Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS), privateMode())) {
            Files.setAttribute(path, "unix:mode", 0600, NOFOLLOW_LINKS);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            if (owner == 0) Files.setAttribute(path, "unix:gid", appGid, NOFOLLOW_LINKS);
            Files.setAttribute(path, "unix:mode", mode, NOFOLLOW_LINKS);
            channel.force(true);
        }
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new Rejected("DIGEST_UNAVAILABLE"); }
    }
    private byte[] protectedFile(Path path, int permissions, int maximum) throws IOException {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                && ((Number)Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS)).longValue() == owner
                && (((Number)Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS)).intValue() & 07777) == permissions,
                "UNSAFE_SNAPSHOT_FILE");
        try (var channel = Files.newByteChannel(path, Set.of(READ, NOFOLLOW_LINKS));
             var input = java.nio.channels.Channels.newInputStream(channel)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            check(bytes.length <= maximum, "SNAPSHOT_FILE_TOO_LARGE"); return bytes;
        }
    }
    public String status(String generation, String revision) {
        generation(generation, revision);
        inspectDirectory(root, 0700); inspectDirectory(root.resolve("releases"), 0700);
        Path release = root.resolve("releases").resolve(generation);
        if (!Files.exists(release, NOFOLLOW_LINKS)) return "MISSING";
        inspectDirectory(release, 0750);
        try {
            var metadata = new Properties();
            metadata.load(new java.io.ByteArrayInputStream(protectedFile(release.resolve("metadata.properties"), 0600, 4096)));
            check(generation.equals(metadata.getProperty("generation")) && revision.equals(metadata.getProperty("revision"))
                    && "STAGED_NOT_ACTIVE".equals(metadata.getProperty("status")), "SNAPSHOT_METADATA_CONFLICT");
            check(digest(protectedFile(release.resolve("runtime-integrations.properties"), 0440, 65536))
                    .equals(metadata.getProperty("payload.sha256")), "SNAPSHOT_CHANGED");
            return "STAGED_NOT_ACTIVE";
        } catch (IOException | IllegalArgumentException e) { throw new Rejected("SNAPSHOT_STATUS_FAILED"); }
    }
    @Override public void stage(String generation, String revision, Map<String, String> values, Collection<String> groups, String role) {
        check("receiver".equals(role), "ROLE_DENIED"); generation(generation, revision);
        byte[] payload = render(values, groups);
        inspectDirectory(root, 0700); inspectDirectory(root.resolve("releases"), 0700);
        Path lockPath = root.resolve("stage.lock"), pending = null;
        try {
            try { Files.createFile(lockPath, privateMode()); Files.setAttribute(lockPath, "unix:mode", 0600, NOFOLLOW_LINKS); }
            catch (FileAlreadyExistsException ignored) { }
            check(Files.isRegularFile(lockPath, NOFOLLOW_LINKS) && !Files.isSymbolicLink(lockPath)
                    && ((Number)Files.getAttribute(lockPath, "unix:uid", NOFOLLOW_LINKS)).longValue() == owner
                    && (((Number)Files.getAttribute(lockPath, "unix:mode", NOFOLLOW_LINKS)).intValue() & 07777) == 0600,
                    "UNSAFE_STAGE_LOCK");
            try (var channel = FileChannel.open(lockPath, WRITE, NOFOLLOW_LINKS)) {
                FileLock lock;
                try { lock = channel.tryLock(); } catch (OverlappingFileLockException e) { throw new Rejected("STAGE_BUSY"); }
                check(lock != null, "STAGE_BUSY");
                try (lock) {
                    Path target = root.resolve("releases").resolve(generation);
                    check(!Files.exists(target, NOFOLLOW_LINKS), "GENERATION_ALREADY_EXISTS");
                    pending = Files.createTempDirectory(root.resolve("releases"), ".pending-",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    Files.setAttribute(pending, "unix:mode", 0700, NOFOLLOW_LINKS);
                    file(pending.resolve("runtime-integrations.properties"), payload, 0440);
                    String metadata = "schema=1\nstatus=STAGED_NOT_ACTIVE\ngeneration=" + generation + "\nrevision=" + revision
                            + "\ngroups=" + String.join(",", new TreeSet<>(groups)) + "\npayload.sha256=" + digest(payload) + "\n";
                    file(pending.resolve("metadata.properties"), metadata.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0600);
                    if (owner == 0) Files.setAttribute(pending, "unix:gid", appGid, NOFOLLOW_LINKS);
                    Files.setAttribute(pending, "unix:mode", 0750, NOFOLLOW_LINKS);
                    directorySync(pending);
                    Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
                    pending = null;
                    directorySync(target.getParent());
                }
            }
        } catch (IOException e) { throw new Rejected("SNAPSHOT_STAGE_FAILED_REVIEW_REQUIRED"); }
        finally {
            if (pending != null) {
                // Only the two fixed files inside this operation's exclusive temporary directory.
                try {
                    Files.deleteIfExists(pending.resolve("runtime-integrations.properties"));
                    Files.deleteIfExists(pending.resolve("metadata.properties")); Files.delete(pending);
                } catch (IOException e) { throw new Rejected("SNAPSHOT_PRIVATE_CLEANUP_FAILED"); }
            }
        }
    }
}
