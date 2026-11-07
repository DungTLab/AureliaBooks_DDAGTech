package com.ddagtech.aureliabooks.runtimeconfig;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Disposable POSIX fixture, NOT a receiver, Docker adapter or VPS installer. */
public final class OfflineConfigStore implements AutoCloseable, StagingConfigEnvelope.StageSink {
    public static final String IMAGE_A = "sha256:" + "a".repeat(64);
    public static final class SimulatedCrash extends Error {
        public SimulatedCrash() { super("SIMULATED_CRASH", null, false, false); }
    }
    @FunctionalInterface public interface Hook { void at(String point); }
    public Hook hook = point -> {};
    public static final class FakeApplication {
        public String image = IMAGE_A, generation, overlay;
        public String database = "synthetic-database-identity";
        public boolean failNext, failAll;
        public Hook beforeRecreate = point -> {};
        void recreate(String image, String generation, String overlay) {
            beforeRecreate.at("recreate");
            this.image = image; this.generation = generation; this.overlay = overlay;
            if (failNext || failAll) { failNext = false; throw new Rejected("SIMULATED_READINESS_FAILURE"); }
        }
    }
    public final Path root;
    public final FakeApplication app = new FakeApplication();
    private final Object owner;
    private static final String PROPS = "runtime-integrations.properties", OVERLAY = "compose.runtime.yml";
    private static final Set<String> CLEAN = Set.of("IDLE", "CONFIRMED", "ROLLED_BACK");

    public OfflineConfigStore() {
        try {
            root = Files.createTempDirectory(Path.of("/tmp"), "aurelia-config-java-", attr(0700));
            mode(root, 0700);
            owner = Files.getAttribute(root, "unix:uid", NOFOLLOW_LINKS);
            directory(root.resolve("releases"), 0700);
            write("confirmed", Map.of("generation", ""));
            write("current", Map.of("generation", ""));
            write("transaction", Map.of("status", "IDLE"));
            write("history", Map.of("generations", ""));
        } catch (IOException e) { throw new Rejected("FIXTURE_INITIALIZATION_FAILED"); }
    }
    private static java.nio.file.attribute.FileAttribute<Set<java.nio.file.attribute.PosixFilePermission>> attr(int mode) {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(switch (mode) {
            case 0700 -> "rwx------"; case 0750 -> "rwxr-x---"; case 0440 -> "r--r-----";
            default -> "rw-------";
        }));
    }
    private static void mode(Path path, int mode) throws IOException {
        Files.setPosixFilePermissions(path, attr(mode).value());
    }
    private static void directory(Path path, int mode) throws IOException {
        Files.createDirectory(path, attr(0700));
        mode(path, mode);
    }
    private void safe(Path path, int mode, boolean directory) throws IOException {
        check(!Files.isSymbolicLink(path) && (directory ? Files.isDirectory(path, NOFOLLOW_LINKS)
                : Files.isRegularFile(path, NOFOLLOW_LINKS)), "UNSAFE_FILE_TYPE");
        check(owner.equals(Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS))
                && Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).equals(attr(mode).value()), "UNSAFE_FILE_PERMISSIONS");
    }
    private void guard() throws IOException {
        safe(root, 0700, true); safe(root.resolve("releases"), 0700, true);
        check("synthetic-database-identity".equals(app.database), "DATABASE_CONTRACT_CHANGED");
    }
    private static void syncDirectory(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, READ, NOFOLLOW_LINKS)) { channel.force(true); }
    }
    private byte[] read(Path path, int mode) throws IOException {
        safe(path, mode, false);
        try (var channel = Files.newByteChannel(path, Set.of(READ, NOFOLLOW_LINKS));
             var stream = Channels.newInputStream(channel)) {
            byte[] data = stream.readNBytes(131073);
            check(data.length <= 131072, "OVERSIZED_FILE");
            return data;
        }
    }
    private static byte[] encode(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        new TreeMap<>(values).forEach((key, value) -> result.append(key).append('=').append(ConfigPolicy.escape(value)).append('\n'));
        return result.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
    private static Map<String, String> decode(byte[] body) throws IOException {
        Properties props = new Properties();
        try { props.load(new ByteArrayInputStream(body)); }
        catch (IllegalArgumentException e) { throw new Rejected("INVALID_METADATA"); }
        Map<String, String> result = new HashMap<>();
        props.forEach((k, v) -> result.put((String)k, (String)v));
        return result;
    }
    private void write(String name, Map<String, String> values) throws IOException {
        hook.at("before_write_" + name);
        Path temporary = Files.createTempFile(root, ".pending-", "", attr(0600));
        try {
            mode(temporary, 0600);
            try (FileChannel channel = FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(encode(values));
                while (buffer.hasRemaining()) channel.write(buffer);
                hook.at("before_fsync_" + name);
                channel.force(true);
            }
            hook.at("before_promote_" + name);
            Files.move(temporary, root.resolve(name + ".properties"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(root);
        } finally { Files.deleteIfExists(temporary); }
    }
    private Map<String, String> metadata(String name) throws IOException {
        return decode(read(root.resolve(name + ".properties"), 0600));
    }
    private String required(Map<String, String> data, String key) {
        check(data.containsKey(key), "INVALID_METADATA"); return data.get(key);
    }
    private String confirmedValue() throws IOException { return required(metadata("confirmed"), "generation"); }
    public String confirmed() {
        try { String value = confirmedValue(); return value.isEmpty() ? null : value; }
        catch (IOException e) { throw new Rejected("INVALID_METADATA"); }
    }
    public String status() {
        try { return required(metadata("transaction"), "status"); }
        catch (IOException e) { throw new Rejected("INVALID_METADATA"); }
    }
    private static String empty(String value) { return value == null ? "" : value; }
    private static void role(String role, String operation) {
        check(("receiver".equals(role) && operation.equals("stage"))
                || ("installer".equals(role) && Set.of("stage", "activate", "recover", "deploy", "rollback").contains(operation)), "ROLE_DENIED");
    }
    private final class Lock implements AutoCloseable {
        final Process process;
        Lock(Process process) { this.process = process; }
        public void close() { stopLock(process); }
    }
    private static void stopLock(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            while (process.isAlive() && System.nanoTime() < deadline) {
                try { process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        check(!process.isAlive(), "LOCK_PROCESS_CLEANUP_FAILED");
    }
    private Lock lock() throws IOException {
        guard();
        Path path = root.resolve("host.lock");
        try { Files.createFile(path, attr(0600)); mode(path, 0600); }
        catch (FileAlreadyExistsException ignored) { /* Existing unsafe locks must be rejected. */ }
        safe(path, 0600, false);
        // Java FileLock uses fcntl on Linux, which does NOT interoperate with
        // the existing deploy.sh flock. Retain the exact OS locking primitive.
        ProcessBuilder builder = new ProcessBuilder("/usr/bin/flock", "-n", "-E", "75", "--",
                path.toString(), "/bin/sh", "-c", "printf L; IFS= read -r ignored");
        builder.environment().clear();
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> marker = reader.submit(() -> process.getInputStream().read());
            int value = marker.get(2, TimeUnit.SECONDS);
            if (value != 'L') {
                check(process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 75, "LOCK_PROCESS_FAILED");
                throw new Rejected("BUSY_SHARED_DEPLOYMENT_LOCK");
            }
            return new Lock(process);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); stopLock(process); throw new Rejected("LOCK_INTERRUPTED");
        } catch (ExecutionException | TimeoutException e) {
            stopLock(process); throw new Rejected("LOCK_DEADLINE_FAILED");
        } catch (RuntimeException e) { stopLock(process); throw e; }
        finally {
            reader.shutdownNow();
            try { check(reader.awaitTermination(2, TimeUnit.SECONDS), "LOCK_READER_CLEANUP_FAILED"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    public void withLock(Runnable action) {
        try (var ignored = lock()) { action.run(); }
        catch (IOException e) { throw new Rejected("FIXTURE_IO_FAILED"); }
    }
    private static String digest(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (Exception e) { throw new Rejected("DIGEST_UNAVAILABLE"); }
    }
    private String overlay(String generation) throws IOException {
        return Files.readString(Path.of("deployment/runtime-config/compose.runtime.template.yml"))
                .replace("__GENERATION__", generation)
                .replace("__APPROVED_READ_GID__", String.valueOf(Files.getAttribute(root, "unix:gid")))
                .replace("__IMMUTABLE_RELEASE_DIRECTORY__", root.resolve("releases").resolve(generation).toString());
    }
    public void stage(String generation, String revision, Map<String, String> values, Collection<String> groups, String role) {
        role(role, "stage"); ConfigPolicy.generation(generation, revision);
        byte[] payload = render(values, groups);
        try (var ignored = lock()) {
            Path release = root.resolve("releases").resolve(generation);
            check(!Files.exists(release, NOFOLLOW_LINKS), "GENERATION_ALREADY_EXISTS");
            directory(release, 0700);
            byte[] compose = overlay(generation).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            Map<String, String> meta = Map.of("generation", generation, "revision", revision,
                    "groups", String.join(",", new TreeSet<>(groups)), "digest.properties", digest(payload), "digest.overlay", digest(compose));
            for (var entry : Map.of(PROPS, payload, OVERLAY, compose, "metadata.properties", encode(meta)).entrySet()) {
                Path path = release.resolve(entry.getKey());
                Files.createFile(path, attr(0600)); mode(path, 0600);
                hook.at("created_private_file");
                try (FileChannel channel = FileChannel.open(path, WRITE, NOFOLLOW_LINKS)) {
                    ByteBuffer buffer = ByteBuffer.wrap(entry.getValue());
                    while (buffer.hasRemaining()) channel.write(buffer);
                    mode(path, entry.getKey().equals("metadata.properties") ? 0600 : 0440);
                    hook.at("before_release_grant");
                    channel.force(true);
                }
            }
            mode(release, 0750); syncDirectory(release); syncDirectory(release.getParent());
        } catch (IOException e) { throw new Rejected("STAGING_IO_FAILED"); }
    }
    private Map<String, String> releaseMetadata(String generation) throws IOException {
        return decode(read(root.resolve("releases").resolve(generation).resolve("metadata.properties"), 0600));
    }
    private static Set<String> groups(String value) {
        if (value.isEmpty()) return Set.of();
        List<String> list = Arrays.asList(value.split(",", -1));
        check(new ArrayList<>(new TreeSet<>(list)).equals(list) && GROUPS.containsAll(list), "INVALID_GROUP_METADATA");
        return new HashSet<>(list);
    }
    public String validate(String generation) {
        ConfigPolicy.generation(generation, "0".repeat(40));
        try {
            Path release = root.resolve("releases").resolve(generation);
            safe(release, 0750, true);
            try (var paths = Files.list(release)) {
                check(paths.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet())
                        .equals(Set.of(PROPS, OVERLAY, "metadata.properties")), "UNEXPECTED_RELEASE_FILE");
            }
            var metadata = releaseMetadata(generation);
            check(generation.equals(metadata.get("generation")), "GENERATION_MISMATCH");
            ConfigPolicy.generation(generation, required(metadata, "revision"));
            groups(required(metadata, "groups"));
            check(digest(read(release.resolve(PROPS), 0440)).equals(metadata.get("digest.properties"))
                    && digest(read(release.resolve(OVERLAY), 0440)).equals(metadata.get("digest.overlay")), "IMMUTABLE_GENERATION_CHANGED");
            check(Arrays.equals(read(release.resolve(OVERLAY), 0440), overlay(generation).getBytes(java.nio.charset.StandardCharsets.US_ASCII)), "OVERLAY_CONTRACT_CHANGED");
            return release.resolve(OVERLAY).toString();
        } catch (IOException e) { throw new Rejected("RELEASE_VALIDATION_FAILED"); }
    }
    private void clean() throws IOException {
        check(CLEAN.contains(required(metadata("transaction"), "status")), "RECOVERY_REQUIRED");
        check(required(metadata("current"), "generation").equals(confirmedValue())
                && confirmedValue().equals(empty(app.generation)), "RUNNING_GENERATION_CONFLICT");
    }
    private void point(String requested, String actual) { hook.at(actual); if (actual.equals(requested)) throw new SimulatedCrash(); }
    public void activate(String generation, String previous, String image, String role, String crash) {
        activate(generation, previous, image, role, crash, false);
    }
    public void rollback(String generation, String previous, String image, String role) {
        activate(generation, previous, image, role, null, true);
    }
    private void activate(String generation, String previous, String image, String role, String crash, boolean rollback) {
        role(role, rollback ? "rollback" : "activate");
        try (var ignored = lock()) {
            clean();
            check(confirmedValue().equals(empty(previous)), "STALE_APPROVAL");
            check(app.image.equals(image), "IMAGE_CONFLICT");
            check(!generation.equals(previous), "GENERATION_ALREADY_ACTIVE");
            String overlay = validate(generation);
            String history = required(metadata("history"), "generations");
            if (rollback) check(Arrays.asList(history.split(",")).contains(generation), "ROLLBACK_TARGET_NOT_PREVIOUSLY_CONFIRMED");
            else if (previous != null) {
                validate(previous);
                check(groups(required(releaseMetadata(generation), "groups")).containsAll(
                        groups(required(releaseMetadata(previous), "groups"))), "PROVIDER_REMOVAL_REQUIRES_SEPARATE_APPROVAL");
            }
            point(crash, "before_journal");
            Map<String, String> transaction = transaction(previous, generation, image, history, "APPLYING");
            write("transaction", transaction); point(crash, "after_journal");
            try {
                write("current", Map.of("generation", generation)); point(crash, "after_pointer");
                app.recreate(image, generation, overlay); point(crash, "after_recreate"); point(crash, "before_confirm");
                write("confirmed", Map.of("generation", generation)); point(crash, "after_confirm");
                write("history", Map.of("generations", history.isEmpty() ? generation : history + "," + generation));
                write("transaction", status(transaction, "CONFIRMED")); point(crash, "after_commit");
            } catch (RuntimeException | IOException e) {
                restore(transaction); throw new Rejected("ACTIVATION_FAILED_ROLLED_BACK");
            }
        } catch (IOException e) { throw new Rejected("ACTIVATION_IO_FAILED"); }
    }
    private Map<String, String> transaction(String old, String target, String image, String history, String status) {
        return Map.of("old", empty(old), "target", empty(target), "image", image, "history_before", history, "status", status);
    }
    private static Map<String, String> status(Map<String, String> transaction, String status) {
        Map<String, String> result = new HashMap<>(transaction); result.put("status", status); return result;
    }
    private void restore(Map<String, String> transaction) throws IOException {
        try {
            String old = required(transaction, "old"), image = required(transaction, "image");
            String overlay = old.isEmpty() ? null : validate(old);
            write("current", Map.of("generation", old));
            app.recreate(image, old.isEmpty() ? null : old, overlay);
            write("confirmed", Map.of("generation", old));
            write("history", Map.of("generations", required(transaction, "history_before")));
            write("transaction", status(transaction, "ROLLED_BACK"));
        } catch (RuntimeException | IOException e) {
            write("transaction", status(transaction, "RECOVERY_FAILED"));
            throw new Rejected("ROLLBACK_FAILED_MANUAL_RECOVERY_REQUIRED");
        }
    }
    public void recover(String role) {
        role(role, "recover");
        try (var ignored = lock()) {
            var transaction = metadata("transaction");
            if (Set.of("APPLYING", "RECOVERY_FAILED").contains(required(transaction, "status"))) restore(transaction);
            clean();
        } catch (IOException e) { throw new Rejected("RECOVERY_IO_FAILED"); }
    }
    public void deploy(String image, String previous, String role) {
        role(role, "deploy"); check(image != null && image.matches("sha256:[0-9a-f]{64}"), "INVALID_IMAGE_ID");
        try (var ignored = lock()) {
            clean(); check(confirmedValue().equals(empty(previous)), "STALE_DEPLOYMENT");
            String overlay = previous == null ? null : validate(previous);
            var transaction = transaction(previous, previous, app.image, required(metadata("history"), "generations"), "APPLYING");
            write("transaction", transaction);
            try { app.recreate(image, previous, overlay); write("transaction", status(transaction, "CONFIRMED")); }
            catch (RuntimeException | IOException e) { restore(transaction); throw new Rejected("IMAGE_DEPLOYMENT_FAILED_ROLLED_BACK"); }
        } catch (IOException e) { throw new Rejected("DEPLOY_IO_FAILED"); }
    }
    public void close() {
        // Only this constructor-created disposable tree is removed; never an external destination.
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        } catch (IOException e) { throw new Rejected("FIXTURE_CLEANUP_FAILED"); }
    }
}
