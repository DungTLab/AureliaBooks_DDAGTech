package com.ddagtech.aureliabooks.runtimeconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigTransportTest {
    @TempDir Path directory;
    private static final String SECRET = "SYNTHETIC_RUNTIME_SECRET_TRANSPORT";
    private void executable(String name, String body) throws Exception {
        Path path = directory.resolve(name); Files.writeString(path, "#!/bin/bash\nset -eu\n" + body);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }
    private void run(String mode, boolean success) throws Exception {
        executable("ssh-keygen", "exit 0\n");
        executable("openssl", "printf '%s\\n' '11111111111111111111111111111111'\n");
        executable("java", "printf '%s' 'PRIVATE_FRAME_ONLY'\n");
        // Remote inherits PATH only. No real SSH connection or provider secrets.
        executable("ssh", """
                [[ -z "${MAIL_PASSWORD:-}" && -z "${VPS_SSH_KEY:-}" ]] || exit 55
                command=${!#}
                case "$command" in
                  *' check '*)
                    MODE
                    printf '%s\\n' READY_STAGE_ONLY_V1 ;;
                  *' stage '*)
                    data=$(cat)
                    [[ "$data" == PRIVATE_FRAME_ONLY ]] || exit 2
                    RESPONSE ;;
                  *) exit 2 ;;
                esac
                """.replace("MODE", mode.equals("not-ready") ? "printf PRIVATE_FRAME_ONLY; exit 1" : ":")
                .replace("RESPONSE", mode.equals("bad-receipt") ? "printf PRIVATE_FRAME_ONLY" :
                        "printf '%s\\n' 'STAGED_NOT_ACTIVE 11111111111111111111111111111111'"));
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", "scripts/runtime-config/stage.sh", "staging", "a".repeat(40));
        builder.environment().clear();
        builder.environment().put("PATH", directory + ":/usr/bin:/bin");
        builder.environment().put("RUNNER_TEMP", directory.toString());
        builder.environment().put("VPS_USER", "fixture"); builder.environment().put("VPS_HOST", "host.example.invalid");
        builder.environment().put("VPS_SSH_KEY", SECRET); builder.environment().put("VPS_SSH_HOST_KEY", "synthetic-host-key");
        builder.environment().put("MAIL_PASSWORD", SECRET);
        Path output = directory.resolve("result"); builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "TRANSPORT_TIMEOUT");
            assertEquals(success, process.exitValue() == 0);
            String report = Files.readString(output);
            assertFalse(report.contains(SECRET) || report.contains("PRIVATE_FRAME_ONLY"), "TRANSPORT_LEAK");
            try (var paths = Files.list(directory)) {
                assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith("aurelia-config.")), "SSH_KEY_FIXTURE_RETAINED");
            }
        } finally { process.destroyForcibly(); }
    }
    @Test void transferUsesPrivatePipeAndOnlyLogsGenerationReceipt() throws Exception { run("ok", true); }
    @Test void failedPreflightCannotPrintRemoteData() throws Exception { run("not-ready", false); }
    @Test void unexpectedStageOutputIsNotPrinted() throws Exception { run("bad-receipt", false); }
}
