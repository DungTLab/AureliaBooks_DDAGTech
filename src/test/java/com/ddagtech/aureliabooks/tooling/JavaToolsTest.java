package com.ddagtech.aureliabooks.tooling;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaToolsTest {
    @TempDir Path root;
    @Test void jsonRoundTripAndUnicode() {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("unicode", "Sách \"A\" \\ \n"); value.put("list", Arrays.asList(null, true, 3));
        Map<String, Object> parsed = ToolJson.object(ToolJson.parse(ToolJson.write(value)));
        assertEquals(value.get("unicode"), parsed.get("unicode")); assertEquals(3, ToolJson.array(parsed.get("list")).size());
        assertEquals("A\n", ToolJson.parse("\"\\u0041\\n\""));
    }
    @ParameterizedTest @ValueSource(strings = {"{\"a\":null,\"a\":1}", "[1,]", "{\"a\":1,}", "01", "true x", "\"\\z\"", "\"\\uZZZZ\"", "{", "[", "\"unclosed", "NaN", "\"line\nfeed\""})
    void malformedJsonRejected(String json) { assertThrows(IllegalArgumentException.class, () -> ToolJson.parse(json)); }
    @Test void jsonDepthBounded() { assertThrows(IllegalArgumentException.class, () -> ToolJson.parse("[".repeat(70) + "0" + "]".repeat(70))); }
    Map<String, Object> run(int number, int attempt, String sha, String event, String conclusion) {
        return Map.of("run_number", number, "run_attempt", attempt, "head_sha", sha, "event", event, "head_branch", "main", "conclusion", conclusion);
    }
    @Test void latestCiAttemptAndPromotionSelection() {
        String sha = "a".repeat(40); var first = run(1, 1, sha, "push", "success"); var retry = run(1, 2, sha, "push", "failure");
        var wrongSha = run(3, 1, "b".repeat(40), "push", "success"); var pullRequest = run(4, 1, sha, "pull_request", "success");
        assertEquals(retry, WorkflowGate.latestRun(Map.of("workflow_runs", List.of(first, retry, wrongSha, pullRequest)), sha, "push", false).orElseThrow());
        var promotion = run(5, 1, sha, "workflow_run", "success");
        assertEquals(promotion, WorkflowGate.latestRun(Map.of("workflow_runs", List.of(promotion, first)), sha, "workflow_run", true).orElseThrow());
        assertTrue(WorkflowGate.latestRun(Map.of("workflow_runs", List.of(first)), sha, "workflow_run", true).isEmpty());
    }
    @Test void productionRequiresAllActualDeploymentSteps() {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (String name : List.of("Verify commit and CI result", "Configure SSH", "Deploy to Staging", "Verify Staging")) steps.add(new HashMap<>(Map.of("name", name, "conclusion", "success")));
        var job = new HashMap<String, Object>(Map.of("name", "Verify CI and Deploy Staging", "conclusion", "success", "steps", steps));
        assertTrue(WorkflowGate.jobsSucceeded(Map.of("jobs", List.of(job))));
        steps.get(2).put("conclusion", "skipped"); assertFalse(WorkflowGate.jobsSucceeded(Map.of("jobs", List.of(job))));
        steps.get(2).put("conclusion", "success"); job.put("name", "Unrelated job"); assertFalse(WorkflowGate.jobsSucceeded(Map.of("jobs", List.of(job))));
    }
    @ParameterizedTest @ValueSource(strings = {"https://127.0.0.1:8080/qa", "http://localhost:8080/qa", "http://example.com:8080/qa", "http://127.0.0.1/qa", "http://127.0.0.1:8080/", "http://user@127.0.0.1:8080/qa", "http://127.0.0.1:8080/qa?x=1", "http://127.0.0.1:8080/qa#x", "http://127.0.0.1:99999/qa"})
    void rejectNonQaOrigins(String url) { assertThrows(IllegalArgumentException.class, () -> R2QaTools.localQaUri(url)); }
    @Test void proxyRetainsLoopbackAndLimits() {
        String config = R2QaTools.nginxConfig(R2QaTools.localQaUri("http://127.0.0.1:8081/qa"), R2QaTools.localQaUri("http://127.0.0.1:8082/qa"), 18081, 18082);
        assertTrue(config.contains("listen 127.0.0.1:18081")); assertTrue(config.contains("listen 127.0.0.1:18082")); assertTrue(config.contains("client_max_body_size 6m")); assertTrue(config.contains("proxy_pass http://127.0.0.1:8082"));
    }
    @Test void passwordReaderPreservesEqualsAndRejectsApplicationDatabase() throws Exception {
        Path file = root.resolve("mysql.env"); String synthetic = "SYNTHETIC_QA_ONLY=a=b";
        Files.writeString(file, "# fixture\nMYSQL_DATABASE=r2_image_qa\nMYSQL_USER=r2_qa\nMYSQL_PASSWORD=" + synthetic + "\n");
        assertTrue(synthetic.equals(R2QaTools.qaPassword(file)), "QA_PASSWORD_ROUND_TRIP_FAILED");
        Files.writeString(file, "MYSQL_DATABASE=aurelia_books_db\nMYSQL_USER=r2_qa\nMYSQL_PASSWORD=" + synthetic + "\n");
        Exception failure = assertThrows(IllegalArgumentException.class, () -> R2QaTools.qaPassword(file));
        assertFalse(failure.toString().contains(synthetic), "QA_PASSWORD_DISCLOSURE");
        Files.writeString(file, "MYSQL_DATABASE=r2_image_qa\nMYSQL_USER=r2_qa\nMYSQL_PASSWORD=x\nMYSQL_PASSWORD=y\n");
        assertThrows(IllegalArgumentException.class, () -> R2QaTools.qaPassword(file));
    }
    @Test void workflowsParseAndKeepDeploymentContracts() throws Exception {
        for (String file : List.of("ci.yml", "deploy-auto.yml", "promote-production.yml", "runtime-config-sync.yml")) {
            String body = Files.readString(Path.of(".github/workflows", file)); assertFalse(body.contains("python3")); assertNotNull(new Yaml().load(body));
        }
        String auto = Files.readString(Path.of(".github/workflows/deploy-auto.yml"));
        assertTrue(auto.contains("WorkflowGate ci")); assertTrue(auto.contains("environment: staging")); assertTrue(auto.contains("if: steps.verify.outputs.deploy == 'true'"));
        String promotion = Files.readString(Path.of(".github/workflows/promote-production.yml"));
        assertTrue(promotion.contains("WorkflowGate promotion")); assertTrue(promotion.contains("environment: production")); assertTrue(promotion.contains("main changed while awaiting approval"));
    }
    @Test void noPythonSourcesOrExecutableCallsRemain() throws Exception {
        try (var files = Files.walk(Path.of("scripts"))) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".py"))); }
        for (Path directory : List.of(Path.of("scripts"), Path.of(".github/workflows"))) {
            try (var files = Files.walk(directory)) {
                for (Path file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".sh") || p.toString().endsWith(".yml")).toList())
                    assertFalse(Files.readString(file).contains("python3"), "PYTHON_EXECUTABLE_DEPENDENCY_REMAINS");
            }
        }
    }
    @Test void hangingChildIsTerminatedWithoutEchoingOutput() throws Exception {
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", System.getProperty("java.class.path"), Child.class.getName(), "hang");
        long start = System.nanoTime();
        Exception failure = assertThrows(Exception.class, () -> ToolProcess.capture(command, Duration.ofMillis(300)));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5, "PROCESS_TIMEOUT_NOT_BOUNDED");
        assertFalse(failure.toString().contains("SYNTHETIC_CHILD_VALUE"), "CHILD_OUTPUT_DISCLOSURE");
    }
    @Test void stderrCannotDeadlockCapture() throws Exception {
        String output = ToolProcess.capture(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", System.getProperty("java.class.path"), Child.class.getName(), "stderr"), Duration.ofSeconds(5));
        assertEquals("DONE", output);
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("hang")) { System.out.print("SYNTHETIC_CHILD_VALUE"); System.out.flush(); Thread.sleep(60_000); }
            else { for (int i = 0; i < 100_000; i++) System.err.print('x'); System.out.print("DONE"); }
        }
    }
}
