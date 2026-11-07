package com.ddagtech.aureliabooks.runtimeconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigInventoryTest {
    @TempDir Path directory;
    private record Result(int exit, String output) {}
    private Result fixture(String mode, String target) throws Exception {
        Path docker = directory.resolve("docker");
        Files.writeString(docker, """
                #!/bin/sh
                [ "$1" = inspect ] && [ "$2" = --format ] || exit 3
                case "$FIXTURE_MODE" in
                  failed) printf PRIVATE_FIXTURE >&2; exit 1;;
                esac
                if [ "$3" = '{{.State.Running}}' ]; then printf true
                elif [ "$FIXTURE_MODE" = unexpected ]; then printf PRIVATE_FIXTURE
                else
                  case "$3" in
                    *'.Destination'*) printf READ_ONLY;;
                    *'=false'*) printf FALSE;;
                    *slice*) printf GOOGLE;;
                    *GOOGLE_CLIENT_ID*) printf PRESENT;;
                  esac
                fi
                """);
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwx------"));
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", "scripts/runtime-config/inspect_vps.sh", target);
        builder.environment().clear(); builder.environment().put("PATH", directory + ":/usr/bin:/bin");
        builder.environment().put("FIXTURE_MODE", mode);
        Path output = directory.resolve("output");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(8, TimeUnit.SECONDS), "INVENTORY_TIMEOUT");
            String body = Files.readString(output);
            assertFalse(body.contains("PRIVATE_FIXTURE"), "PRIVATE_FIXTURE_LEAK");
            return new Result(process.exitValue(), body);
        } finally { process.destroyForcibly(); }
    }
    @Test void inventoryOnlyEmitsMarkers() throws Exception {
        Result result = fixture("normal", "staging"); assertEquals(0, result.exit());
        for (String marker : new String[]{"env.GOOGLE_CLIENT_ID=PRESENT", "env.GOOGLE_CLIENT_SECRET=MISSING",
                "profile.google=DECLARED_IN_ENV", "mount.runtime=READ_ONLY", "flag.R2_ENABLED=EXPLICIT_FALSE", "integration=NOT_VERIFIED"})
            assertTrue(result.output().contains(marker), "METADATA_MARKER_MISSING");
    }
    @Test void productionTargetIsExplicit() throws Exception { assertEquals(0, fixture("normal", "production").exit()); }
    @Test void arbitraryTargetRejected() throws Exception { assertEquals(2, fixture("normal", "anything").exit()); }
    @Test void failureDetailsSuppressed() throws Exception { assertNotEquals(0, fixture("failed", "staging").exit()); }
    @Test void unexpectedOutputSuppressed() throws Exception { assertNotEquals(0, fixture("unexpected", "staging").exit()); }
}
