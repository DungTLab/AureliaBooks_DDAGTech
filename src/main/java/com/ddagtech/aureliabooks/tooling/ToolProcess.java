package com.ddagtech.aureliabooks.tooling;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** Bounded process capture. Diagnostics deliberately exclude command output. */
public final class ToolProcess {
    private ToolProcess() {}
    public static String capture(List<String> command, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> output = reader.submit(() -> {
                try (var stream = process.getInputStream()) {
                    byte[] data = stream.readNBytes(16 * 1024 * 1024 + 1);
                    ToolJson.require(data.length <= 16 * 1024 * 1024, "PROCESS_OUTPUT_TOO_LARGE"); return data;
                }
            });
            try {
                ToolJson.require(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS), "PROCESS_TIMEOUT");
                ToolJson.require(process.exitValue() == 0, "PROCESS_FAILED");
                return new String(output.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly(); process.getInputStream().close();
                output.cancel(true); reader.shutdownNow();
            }
        }
    }
}
