package com.ddagtech.aureliabooks.runtimeconfig;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** One audited host-script integration; does not deploy or change APP_IMAGE/.env. */
public final class RuntimeDeployIntegration {
    private RuntimeDeployIntegration() {}
    static String originalCompose(String environment) {
        return "compose() {\n  docker compose \\\n    -f compose.yaml \\\n" + (environment.equals("production") ? "    -f compose.caddy.yaml \\\n" : "") + "    \"$@\"\n}";
    }
    static String patch(String source, String environment) {
        check(Set.of("staging", "production").contains(environment), "INVALID_ENVIRONMENT");
        String old = originalCompose(environment);
        check(source.indexOf(old) >= 0 && source.indexOf(old) == source.lastIndexOf(old), "DEPLOY_FUNCTION_CONTRACT_CHANGED");
        String replacement = "compose() {\n  # AURELIA_RUNTIME_INTEGRATED_V1\n"
                + "  local runtime=/etc/aureliabooks-runtime/" + environment + "/active.compose.yaml\n"
                + "  local -a files=(-f compose.yaml" + (environment.equals("production") ? " -f compose.caddy.yaml" : "") + ")\n"
                + "  /usr/bin/sudo -n /usr/local/sbin/aureliabooks-runtime-stage verify-active " + environment + " \"$SHA\" >/dev/null || return 1\n"
                + "  if [[ -f \"$runtime\" && ! -L \"$runtime\" ]]; then\n    files+=(-f \"$runtime\")\n"
                + "  elif [[ -e \"$runtime\" || -L \"$runtime\" ]]; then\n    return 1\n  fi\n"
                + "  docker compose \"${files[@]}\" \"$@\"\n}";
        return source.replace(old, replacement);
    }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static void install(Path script, Path backup, String expectedHash, String environment, long scriptOwner) throws Exception {
        check(Files.isRegularFile(script, NOFOLLOW_LINKS) && !Files.isSymbolicLink(script), "UNSAFE_DEPLOY_SCRIPT");
        check(((Number)Files.getAttribute(script, "unix:uid", NOFOLLOW_LINKS)).longValue() == scriptOwner
                && (((Number)Files.getAttribute(script, "unix:mode", NOFOLLOW_LINKS)).intValue() & 07777) == 0700, "UNEXPECTED_DEPLOY_PERMISSIONS");
        byte[] current = Files.readAllBytes(script), original = current;
        if (Files.exists(backup, NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(backup, NOFOLLOW_LINKS) && !Files.isSymbolicLink(backup)
                    && (((Number)Files.getAttribute(backup, "unix:mode", NOFOLLOW_LINKS)).intValue() & 07777) == 0600, "UNSAFE_DEPLOY_BACKUP");
            original = Files.readAllBytes(backup);
        }
        check(expectedHash.equals(digest(original)), "DEPLOY_BASELINE_CHANGED");
        byte[] updated = patch(new String(original, java.nio.charset.StandardCharsets.UTF_8), environment).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (Arrays.equals(current, updated)) return;
        check(Arrays.equals(current, original), "DEPLOY_SCRIPT_CHANGED");
        if (!Files.exists(backup, NOFOLLOW_LINKS)) {
            try (var file = FileChannel.open(backup, Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                Files.setAttribute(backup, "unix:mode", 0600, NOFOLLOW_LINKS); ByteBuffer data = ByteBuffer.wrap(original); while (data.hasRemaining()) file.write(data); file.force(true);
            }
        }
        long gid = ((Number)Files.getAttribute(script, "unix:gid", NOFOLLOW_LINKS)).longValue();
        Path temp = Files.createTempFile(script.getParent(), ".deploy-runtime-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var file = FileChannel.open(temp, WRITE, NOFOLLOW_LINKS)) { ByteBuffer data = ByteBuffer.wrap(updated); while (data.hasRemaining()) file.write(data); file.force(true); }
            Files.setAttribute(temp, "unix:uid", Math.toIntExact(scriptOwner), NOFOLLOW_LINKS); Files.setAttribute(temp, "unix:gid", Math.toIntExact(gid), NOFOLLOW_LINKS); Files.setAttribute(temp, "unix:mode", 0700, NOFOLLOW_LINKS);
            Files.move(temp, script, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var directory = FileChannel.open(script.getParent(), READ, NOFOLLOW_LINKS)) { directory.force(true); }
        } finally { Files.deleteIfExists(temp); }
    }
    static void deployed(String environment) throws Exception {
        RuntimeActivation.deployed(environment).verify();
        boolean production = environment.equals("production");
        install(Path.of(production ? "/opt/apps/swp391/scripts/deploy.sh" : "/opt/apps/swp391-staging/scripts/deploy-staging.sh"),
                Path.of("/var/lib/aureliabooks-runtime", environment, "deploy-before-runtime.sh"),
                production ? "99b9e8bb5ee9f7b654d19ff129ad16c661eea78b19c4980e07d82bbd4dc0128c" : "5a95df9a265d9d4f7d508e76653673406a00aa5c6044f9db01e068cfbe9036ac", environment, 1001);
    }
}
