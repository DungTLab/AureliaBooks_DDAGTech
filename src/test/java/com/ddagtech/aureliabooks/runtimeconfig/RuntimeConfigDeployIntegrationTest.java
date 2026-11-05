package com.ddagtech.aureliabooks.runtimeconfig;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;

class RuntimeConfigDeployIntegrationTest {
    @TempDir Path directory;
    String source(String environment) {
        return "#!/bin/bash\nset -euo pipefail\nSHA=" + "a".repeat(40) + "\n" + RuntimeDeployIntegration.originalCompose(environment)
                + "\ncompose config --quiet\ncompose up -d --no-deps --force-recreate app\ncompose up -d --no-deps --force-recreate app\n";
    }
    void executable(Path file, String source) throws Exception { Files.writeString(file, source); Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------")); }
    @ParameterizedTest @ValueSource(strings = {"staging", "production"})
    void allDeployValidationAndRollbackCallsRetainOverlay(String environment) throws Exception {
        Path bin = Files.createDirectory(directory.resolve("bin")), overlay = Files.createDirectory(directory.resolve("public"));
        Files.writeString(overlay.resolve("active.compose.yaml"), "services: {}\n");
        Path log = directory.resolve("calls");
        executable(bin.resolve("sudo"), "#!/bin/bash\n[[ \"$*\" == *'verify-active " + environment + " '* ]] || exit 3\nexit 0\n");
        executable(bin.resolve("docker"), "#!/bin/bash\nprintf '%s\\n' \"$*\" >> \"$CALLS\"\n");
        String patched = RuntimeDeployIntegration.patch(source(environment), environment)
                .replace("/usr/bin/sudo", bin.resolve("sudo").toString())
                .replace("/etc/aureliabooks-runtime/" + environment, overlay.toString());
        Path script = directory.resolve("deploy.sh"); executable(script, patched);
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", script.toString()); builder.environment().clear();
        builder.environment().putAll(Map.of("PATH", bin + ":/usr/bin:/bin", "CALLS", log.toString()));
        Process process = builder.redirectErrorStream(true).redirectOutput(directory.resolve("output").toFile()).start();
        try { assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); }
        finally { process.destroyForcibly(); }
        List<String> calls = Files.readAllLines(log); assertEquals(3, calls.size());
        for (String call : calls) {
            assertTrue(call.contains("-f compose.yaml")); assertTrue(call.contains("-f " + overlay.resolve("active.compose.yaml")));
            assertEquals(environment.equals("production"), call.contains("-f compose.caddy.yaml"));
        }
    }
    @Test void recoveryFailureCannotFallThroughToBaseCompose() throws Exception {
        Path deny = directory.resolve("deny"), docker = directory.resolve("docker"), log = directory.resolve("calls");
        executable(deny, "#!/bin/bash\nexit 2\n"); executable(docker, "#!/bin/bash\nprintf CALLED > \"$CALLS\"\n");
        Path script = directory.resolve("deploy.sh"); executable(script, RuntimeDeployIntegration.patch(source("staging"), "staging").replace("/usr/bin/sudo", deny.toString()));
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", script.toString()); builder.environment().clear(); builder.environment().putAll(Map.of("PATH", directory + ":/usr/bin:/bin", "CALLS", log.toString()));
        Process process = builder.start(); try { assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertNotEquals(0, process.exitValue()); assertFalse(Files.exists(log)); } finally { process.destroyForcibly(); }
    }
    @Test void installIsImmutableBackedUpAndIdempotent() throws Exception {
        Path script = directory.resolve("deploy.sh"), backup = directory.resolve("before.sh"); String source = source("staging"); executable(script, source);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(script)));
        long uid = ((Number)Files.getAttribute(script, "unix:uid")).longValue();
        RuntimeDeployIntegration.install(script, backup, hash, "staging", uid);
        assertEquals(source, Files.readString(backup)); assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(backup));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(script)); byte[] installed = Files.readAllBytes(script);
        RuntimeDeployIntegration.install(script, backup, hash, "staging", uid); assertArrayEquals(installed, Files.readAllBytes(script));
        Files.writeString(script, "manual unknown change"); assertThrows(Rejected.class, () -> RuntimeDeployIntegration.install(script, backup, hash, "staging", uid)); assertEquals(source, Files.readString(backup));
    }
    @Test void unexpectedBaselineAndDuplicateComposeFunctionsRejected() throws Exception {
        assertThrows(Rejected.class, () -> RuntimeDeployIntegration.patch("unknown source", "staging"));
        assertThrows(Rejected.class, () -> RuntimeDeployIntegration.patch(source("staging") + RuntimeDeployIntegration.originalCompose("staging"), "staging"));
        Path script = directory.resolve("deploy.sh"); executable(script, source("staging")); long uid = ((Number)Files.getAttribute(script, "unix:uid")).longValue();
        assertThrows(Rejected.class, () -> RuntimeDeployIntegration.install(script, directory.resolve("backup"), "0".repeat(64), "staging", uid));
        assertFalse(Files.exists(directory.resolve("backup")));
    }
    @Test void symlinkScriptRejectedWithoutFollowingIt() throws Exception {
        Path actual = directory.resolve("actual"); executable(actual, source("staging")); Path link = directory.resolve("link"); Files.createSymbolicLink(link, actual);
        assertThrows(Rejected.class, () -> RuntimeDeployIntegration.install(link, directory.resolve("backup"), "0".repeat(64), "staging", ((Number)Files.getAttribute(actual, "unix:uid")).longValue()));
    }
}
