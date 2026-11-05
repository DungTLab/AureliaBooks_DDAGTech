package com.ddagtech.aureliabooks.runtimeconfig;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;

/** Dedicated stdin/env boundary; never a Spring application or deployment launcher. */
public final class RuntimeConfigCli {
    private RuntimeConfigCli() {}
    private static byte[] frameFromStdin() {
        ExecutorService io = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "runtime-frame-input"); thread.setDaemon(true); return thread;
        });
        // FileChannel interruption closes the pipe rather than leaving a blocking InputStream read.
        try (FileInputStream stream = new FileInputStream(FileDescriptor.in); FileChannel channel = stream.getChannel()) {
            Future<byte[]> task = io.submit(() -> {
                ByteBuffer bytes = ByteBuffer.allocate(131073);
                while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
                check(bytes.position() <= 131072, "ENVELOPE_TOO_LARGE");
                return Arrays.copyOf(bytes.array(), bytes.position());
            });
            return task.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new Rejected("INPUT_INTERRUPTED");
        } catch (Exception e) { throw new Rejected("INPUT_FAILED_OR_TIMED_OUT"); }
        finally {
            io.shutdownNow();
            try { check(io.awaitTermination(2, TimeUnit.SECONDS), "INPUT_CLEANUP_FAILED"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    public static void main(String[] args) {
        int exit = 0;
        try {
            check(args.length >= 3, "INVALID_ARGUMENTS");
            String mode = args[0], environment = args[1], revision = args[2];
            check(Set.of("staging", "production").contains(environment), "INVALID_TARGET_ENVIRONMENT");
            generation("0".repeat(32), revision);
            if (mode.equals("prepare")) {
                check(args.length == 4, "INVALID_ARGUMENTS"); generation(args[3], revision);
                Map<String, String> values = new HashMap<>();
                // Explicit allowlist only. R2 credentials/metadata are NOT transferred while disabled.
                FIELDS.forEach((key, field) -> { if (!field.group().equals("r2")) {
                    String value = System.getenv(key); if (value != null) values.put(key, value);
                }});
                for (String key : FLAGS.keySet()) {
                    String value = System.getenv(key); values.put(key, value == null ? "false" : value);
                }
                long now = Clock.systemUTC().instant().getEpochSecond();
                System.out.write(new StagingConfigEnvelope(environment, args[3], revision, now, now + 300, values, GROUPS).encode());
                System.out.flush(); check(!System.out.checkError(), "FRAME_OUTPUT_FAILED");
            } else {
                check(args.length == (mode.equals("status") ? 4 : 3), "INVALID_ARGUMENTS");
                if (mode.equals("status")) {
                    System.out.println(RuntimeSnapshotStore.deployed(environment).status(args[3], revision));
                } else if (mode.equals("check")) {
                    RuntimeSnapshotStore.deployed(environment); System.out.println("READY_STAGE_ONLY_V1");
                } else if (mode.equals("validate")) {
                    StagingConfigEnvelope.validate(frameFromStdin(), environment, revision, Clock.systemUTC(), "receiver");
                    System.out.println("VALID_STAGE_FRAME");
                } else if (mode.equals("stage")) {
                    RuntimeSnapshotStore store = RuntimeSnapshotStore.deployed(environment);
                    String id = StagingConfigEnvelope.receive(frameFromStdin(), environment, revision, Clock.systemUTC(), "receiver", store);
                    System.out.println("STAGED_NOT_ACTIVE " + id);
                } else throw new Rejected("UNSUPPORTED_ACTION");
            }
        } catch (Exception e) {
            // Never print exceptions, original messages, environment values or child diagnostics.
            System.err.println("RUNTIME_CONFIG_REJECTED"); exit = 2;
        }
        System.exit(exit);
    }
}
