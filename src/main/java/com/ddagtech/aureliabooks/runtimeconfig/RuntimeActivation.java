package com.ddagtech.aureliabooks.runtimeconfig;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.regex.Pattern;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Caller must hold the environment's existing deploy.lock (flock), including recovery. */
public final class RuntimeActivation {
    interface Engine {
        String preflight() throws Exception;
        void recreate() throws Exception;
        boolean ready(String image, String generation, boolean google) throws Exception;
    }
    private final String environment;
    private final Path privateRoot, publicRoot;
    private final long owner;
    private final int gid;
    private final RuntimeSnapshotStore store;
    private final Engine engine;
    RuntimeActivation(String environment, Path privateRoot, Path publicRoot, long owner, int gid, Engine engine) {
        check(Set.of("staging", "production").contains(environment), "INVALID_ENVIRONMENT");
        this.environment = environment; this.privateRoot = privateRoot; this.publicRoot = publicRoot;
        this.owner = owner; this.gid = gid; this.engine = engine;
        this.store = new RuntimeSnapshotStore(privateRoot, owner, gid);
        directory(publicRoot, 0755);
    }
    static RuntimeActivation deployed(String environment) {
        RuntimeSnapshotStore.deployed(environment);
        for (Path parent : List.of(Path.of("/etc"), Path.of("/etc/aureliabooks-runtime"))) {
            try {
                check(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)
                        && ((Number)Files.getAttribute(parent, "unix:uid", NOFOLLOW_LINKS)).longValue() == 0
                        && (((Number)Files.getAttribute(parent, "unix:mode", NOFOLLOW_LINKS)).intValue() & 0022) == 0, "UNSAFE_ACTIVATION_PARENT");
            } catch (IOException e) { throw new Rejected("ACTIVATION_NOT_PROVISIONED"); }
        }
        return new RuntimeActivation(environment, Path.of("/var/lib/aureliabooks-runtime", environment),
                Path.of("/etc/aureliabooks-runtime", environment), 0, 999, new RuntimeDocker(environment));
    }
    Path overlayPath() { return publicRoot.resolve("active.compose.yaml"); }
    Path journalPath() { return privateRoot.resolve("activation.pending"); }
    private void directory(Path path, int mode) {
        try {
            check(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                    && ((Number)Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS)).longValue() == owner
                    && (((Number)Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS)).intValue() & 07777) == mode, "UNSAFE_ACTIVATION_DIRECTORY");
        } catch (IOException e) { throw new Rejected("ACTIVATION_DIRECTORY_FAILED"); }
    }
    private byte[] read(Path path, int mode, int limit) throws IOException {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                && ((Number)Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS)).longValue() == owner
                && (((Number)Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS)).intValue() & 07777) == mode, "UNSAFE_ACTIVATION_FILE");
        try (var channel = Files.newByteChannel(path, Set.of(READ, NOFOLLOW_LINKS)); var input = java.nio.channels.Channels.newInputStream(channel)) {
            byte[] bytes = input.readNBytes(limit + 1); check(bytes.length <= limit, "ACTIVATION_FILE_TOO_LARGE"); return bytes;
        }
    }
    private static void sync(Path directory) throws IOException { try (var channel = FileChannel.open(directory, READ, NOFOLLOW_LINKS)) { channel.force(true); } }
    private void atomic(Path path, byte[] bytes, int mode) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), ".runtime-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var channel = FileChannel.open(temp, WRITE, NOFOLLOW_LINKS)) {
                Files.setAttribute(temp, "unix:mode", 0600, NOFOLLOW_LINKS); ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                Files.setAttribute(temp, "unix:mode", mode, NOFOLLOW_LINKS); channel.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); sync(path.getParent());
        } finally { Files.deleteIfExists(temp); }
    }
    public String render(String generation, String revision) {
        generation(generation, revision);
        return "services:\n  app:\n    environment:\n"
                + "      SPRING_CONFIG_ADDITIONAL_LOCATION: \"file:/run/aureliabooks-config/runtime-integrations.properties\"\n"
                + "      SPRING_PROFILES_ACTIVE: \"google\"\n      SERVER_FORWARD_HEADERS_STRATEGY: \"framework\"\n"
                + "      R2_ENABLED: \"false\"\n      R2_UPLOAD_ENABLED: \"false\"\n"
                + "    labels:\n      com.ddagtech.aureliabooks.runtime-config-generation: \"" + generation + "\"\n"
                + "      com.ddagtech.aureliabooks.runtime-config-revision: \"" + revision + "\"\n"
                + "    group_add:\n      - \"" + gid + "\"\n    volumes:\n      - type: bind\n"
                + "        source: \"" + privateRoot.resolve("releases").resolve(generation) + "\"\n"
                + "        target: /run/aureliabooks-config\n        read_only: true\n        bind:\n          create_host_path: false\n";
    }
    private record Active(String generation, String revision) {}
    private Active parseOverlay(byte[] bytes) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        var id = Pattern.compile("runtime-config-generation: \"([a-f0-9]{32})\"").matcher(text);
        var sha = Pattern.compile("runtime-config-revision: \"([a-f0-9]{40})\"").matcher(text);
        check(id.find() && sha.find(), "UNKNOWN_ACTIVE_OVERLAY"); Active active = new Active(id.group(1), sha.group(1));
        check(text.equals(render(active.generation, active.revision)), "ACTIVE_OVERLAY_CHANGED"); return active;
    }
    private Active active() throws IOException {
        if (!Files.exists(overlayPath(), NOFOLLOW_LINKS)) return null;
        Active active = parseOverlay(read(overlayPath(), 0644, 8192)); snapshot(active.generation, active.revision); return active;
    }
    private void snapshot(String id, String revision) throws IOException {
        check("STAGED_NOT_ACTIVE".equals(store.status(id, revision)), "SNAPSHOT_REQUIRED");
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(read(privateRoot.resolve("releases").resolve(id).resolve("runtime-integrations.properties"), 0440, 65536)));
        Set<String> allowed = new HashSet<>(FLAGS.values());
        FIELDS.values().stream().filter(field -> !field.group().equals("r2")).forEach(field -> allowed.add(field.target()));
        allowed.add("spring.mail.properties.mail.smtp.starttls.required");
        check(allowed.containsAll(properties.stringPropertyNames()), "UNEXPECTED_RUNTIME_PROPERTY");
        for (String key : List.of("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "spring.mail.host", "spring.mail.username", "spring.mail.password", "vnpay.tmn-code", "vnpay.hash-secret"))
            check(!properties.getProperty(key, "").isBlank(), "INCOMPLETE_ACTIVATION_SNAPSHOT");
        for (String key : FLAGS.values()) check("false".equals(properties.getProperty(key)), "R2_MUST_REMAIN_DISABLED");
        String domain = environment.equals("staging") ? "aurelia-staging.dzungtlab.id.vn" : "aureliabooks.dzungtlab.id.vn";
        check(("https://" + domain + "/payment/vnpay-return").equals(properties.getProperty("vnpay.return-url")), "VNPAY_RETURN_URL_ENVIRONMENT_MISMATCH");
        if (owner == 0) check(((Number)Files.getAttribute(privateRoot.resolve("releases").resolve(id), "unix:gid", NOFOLLOW_LINKS)).intValue() == gid, "READ_GID_CHANGED");
    }
    public String verify() {
        directory(privateRoot, 0700); directory(publicRoot, 0755);
        check(!Files.exists(journalPath(), NOFOLLOW_LINKS), "ACTIVATION_RECOVERY_REQUIRED");
        try { Active active = active(); return active == null ? "NO_ACTIVE_RUNTIME" : "ACTIVE " + active.generation; }
        catch (IOException e) { throw new Rejected("ACTIVE_VERIFICATION_FAILED"); }
    }
    public String activateGoogle(String id, String revision) {
        verify(); generation(id, revision);
        try {
            snapshot(id, revision); String image = engine.preflight();
            check(image.matches("ghcr.io/dungtlab/aureliabooks_ddagtech:sha-[a-f0-9]{40}"), "UNEXPECTED_IMAGE");
            byte[] previous = Files.exists(overlayPath(), NOFOLLOW_LINKS) ? read(overlayPath(), 0644, 8192) : new byte[0];
            Properties journal = new Properties(); journal.setProperty("image", image);
            journal.setProperty("previous", Base64.getEncoder().encodeToString(previous));
            var output = new ByteArrayOutputStream(); journal.store(output, "Runtime activation recovery; no credentials");
            atomic(journalPath(), output.toByteArray(), 0600);
            try {
                atomic(overlayPath(), render(id, revision).getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0644);
                engine.recreate(); check(engine.ready(image, id, true), "NEW_CONFIG_NOT_READY");
                Files.delete(journalPath()); sync(privateRoot); return "ACTIVE " + id;
            } catch (Exception e) {
                try { recover(); } catch (Exception recovery) { throw new Rejected("ACTIVATION_AND_RECOVERY_FAILED"); }
                throw new Rejected("ACTIVATION_FAILED_PREVIOUS_CONFIG_RESTORED");
            }
        } catch (IOException e) { throw new Rejected("ACTIVATION_IO_REVIEW_REQUIRED"); }
        catch (Rejected e) { throw e; }
        catch (Exception e) { throw new Rejected("ACTIVATION_REJECTED"); }
    }
    public String recover() {
        directory(privateRoot, 0700); directory(publicRoot, 0755);
        try {
            Properties journal = new Properties(); journal.load(new ByteArrayInputStream(read(journalPath(), 0600, 16384)));
            String image = journal.getProperty("image"); check(image != null && image.equals(engine.preflight()), "RECOVERY_IMAGE_CHANGED");
            byte[] previous = Base64.getDecoder().decode(journal.getProperty("previous"));
            Active old = previous.length == 0 ? null : parseOverlay(previous);
            if (old != null) snapshot(old.generation, old.revision);
            if (previous.length == 0) { Files.deleteIfExists(overlayPath()); sync(publicRoot); }
            else atomic(overlayPath(), previous, 0644);
            engine.recreate(); check(engine.ready(image, old == null ? null : old.generation, old != null), "RECOVERY_NOT_READY");
            Files.delete(journalPath()); sync(privateRoot); return "PREVIOUS_RUNTIME_RESTORED";
        } catch (Exception e) { throw new Rejected("RUNTIME_RECOVERY_REQUIRED"); }
    }
}
