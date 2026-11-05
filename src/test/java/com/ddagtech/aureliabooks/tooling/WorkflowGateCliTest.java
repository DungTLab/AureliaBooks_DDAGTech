package com.ddagtech.aureliabooks.tooling;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Fake gh only; exercises CLI exit codes and GitHub outputs without network/authentication. */
class WorkflowGateCliTest {
    @TempDir Path root;
    record Result(int code, String output, String githubOutput) {}
    Result run(String mode, String scenario) throws Exception {
        String sha = "a".repeat(40);
        Files.writeString(root.resolve("branch.json"), ToolJson.write(Map.of("commit", Map.of("sha", scenario.equals("stale") ? "b".repeat(40) : sha))));
        Map<String, Object> run = new HashMap<>(Map.of("run_number", 1, "run_attempt", 1, "id", 99, "head_sha", sha, "event", mode.equals("ci") ? "push" : "workflow_run", "head_branch", "main", "status", "completed", "conclusion", scenario.equals("failure") ? "failure" : "success"));
        Files.writeString(root.resolve("runs.json"), ToolJson.write(Map.of("workflow_runs", scenario.equals("missing") ? List.of() : List.of(run))));
        List<Map<String, String>> steps = new ArrayList<>();
        for (String name : List.of("Verify commit and CI result", "Configure SSH", "Deploy to Staging", "Verify Staging"))
            steps.add(Map.of("name", name, "conclusion", scenario.equals("skipped-step") && name.equals("Deploy to Staging") ? "skipped" : "success"));
        Files.writeString(root.resolve("jobs.json"), ToolJson.write(Map.of("jobs", List.of(Map.of("name", "Verify CI and Deploy Staging", "conclusion", "success", "steps", steps)))));
        Path gh = root.resolve("gh"); Files.writeString(gh, """
                #!/bin/sh
                case "$2" in
                  */branches/main) cat "$FIXTURES/branch.json" ;;
                  */jobs?*) cat "$FIXTURES/jobs.json" ;;
                  */runs?*) cat "$FIXTURES/runs.json" ;;
                  *) exit 1 ;;
                esac
                """);
        Files.setPosixFilePermissions(gh, PosixFilePermissions.fromString("rwx------"));
        Path output = root.resolve("github-output"); Files.writeString(output, ""); Path captured = root.resolve("captured");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", System.getProperty("java.class.path"), WorkflowGate.class.getName(), mode);
        builder.environment().clear(); builder.environment().putAll(Map.of("PATH", root + ":/usr/bin:/bin", "FIXTURES", root.toString(), "REPOSITORY", "fixture/repo", "DEPLOY_SHA", sha, "GITHUB_OUTPUT", output.toString(), "GH_TOKEN", "SYNTHETIC_GH_TOKEN_ONLY"));
        Process child = builder.redirectErrorStream(true).redirectOutput(captured.toFile()).start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "MOCK_GATE_TIMEOUT");
            String stdout = Files.readString(captured); assertFalse(stdout.contains("SYNTHETIC_GH_TOKEN_ONLY"), "TOKEN_DISCLOSURE");
            return new Result(child.exitValue(), stdout, Files.readString(output));
        } finally { child.descendants().forEach(ProcessHandle::destroyForcibly); child.destroyForcibly(); }
    }
    @Test void successfulCiWritesOnlyValidatedOutputs() throws Exception {
        Result result = run("ci", "success"); assertEquals(0, result.code()); assertEquals("deploy=true\nsha=" + "a".repeat(40) + "\n", result.githubOutput());
    }
    @Test void failedCiCannotDeploy() throws Exception { Result result = run("ci", "failure"); assertEquals(1, result.code()); assertTrue(result.githubOutput().isEmpty()); }
    @Test void staleCiSkipsWithoutDeployOutput() throws Exception { Result result = run("ci", "stale"); assertEquals(0, result.code()); assertTrue(result.githubOutput().isEmpty()); }
    @Test void successfulPromotionRequiresJobEvidence() throws Exception { assertEquals(0, run("promotion", "success").code()); }
    @ParameterizedTest @ValueSource(strings = {"failure", "missing", "stale", "skipped-step"})
    void invalidPromotionFailsClosed(String scenario) throws Exception { Result result = run("promotion", scenario); assertEquals(1, result.code()); assertTrue(result.githubOutput().isEmpty()); }
}
