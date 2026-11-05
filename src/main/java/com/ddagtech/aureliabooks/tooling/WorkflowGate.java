package com.ddagtech.aureliabooks.tooling;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.ddagtech.aureliabooks.tooling.ToolJson.*;

/** Equivalent Java release gates; GH calls only occur when this CLI is explicitly run. */
public final class WorkflowGate {
    public static Optional<Map<String, Object>> latestRun(Map<String, Object> document, String sha, String event, boolean successfulOnly) {
        return array(document.get("workflow_runs")).stream().map(ToolJson::object)
                .filter(r -> sha.equals(r.get("head_sha")) && event.equals(r.get("event")) && "main".equals(r.get("head_branch")))
                .filter(r -> !successfulOnly || "success".equals(r.get("conclusion")))
                .max(Comparator.<Map<String, Object>>comparingLong(r -> number(r.get("run_number")))
                        .thenComparingLong(r -> number(r.getOrDefault("run_attempt", 1))));
    }
    private static long number(Object value) {
        require(value instanceof Number, "INVALID_RUN_NUMBER");
        return new java.math.BigDecimal(value.toString()).longValueExact();
    }
    public static boolean jobsSucceeded(Map<String, Object> document) {
        Set<String> required = Set.of("Verify commit and CI result", "Configure SSH", "Deploy to Staging", "Verify Staging");
        return array(document.get("jobs")).stream().map(ToolJson::object).anyMatch(job -> {
            if (!"Verify CI and Deploy Staging".equals(job.get("name")) || !"success".equals(job.get("conclusion"))) return false;
            Set<Object> passed = new HashSet<>();
            for (Object step : array(job.get("steps"))) {
                var item = object(step); if ("success".equals(item.get("conclusion"))) passed.add(item.get("name"));
            }
            return passed.containsAll(required);
        });
    }
    private static Map<String, Object> api(String path) throws Exception {
        return object(parse(ToolProcess.capture(List.of("gh", "api", path), Duration.ofSeconds(25))));
    }
    private static boolean latest(String repo, String sha) throws Exception {
        return sha.equals(object(api("repos/" + repo + "/branches/main").get("commit")).get("sha"));
    }
    public static void main(String[] args) {
        try {
            require(args.length == 1 && Set.of("ci", "promotion").contains(args[0]), "INVALID_GATE");
            String repo = System.getenv("REPOSITORY"), sha = System.getenv("DEPLOY_SHA");
            require(repo != null && repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"), "INVALID_REPOSITORY");
            require(sha != null && sha.matches("[a-f0-9]{40}"), "INVALID_SHA");
            boolean promotion = args[0].equals("promotion");
            if (!latest(repo, sha)) {
                require(!promotion, "NOT_MAIN_HEAD"); System.out.println("SKIP: Commit is no longer main HEAD"); return;
            }
            String workflow = promotion ? "deploy-auto.yml" : "ci.yml";
            String path = "repos/" + repo + "/actions/workflows/" + workflow + "/runs?head_sha=" + sha
                    + (promotion ? "&per_page=100" : "&event=push&per_page=30");
            for (int attempt = 0; attempt < (promotion ? 1 : 30); attempt++) {
                var selected = latestRun(api(path), sha, promotion ? "workflow_run" : "push", promotion);
                if (selected.isPresent()) {
                    var run = selected.get();
                    if (promotion) {
                        long id = number(run.get("id")); require(id > 0, "INVALID_RUN_ID");
                        require(jobsSucceeded(api("repos/" + repo + "/actions/runs/" + id + "/jobs?per_page=100")), "STAGING_JOB_NOT_VERIFIED");
                        System.out.println("Staging deployment job and all required steps: PASS"); return;
                    }
                    if ("completed".equals(run.get("status"))) {
                        require("success".equals(run.get("conclusion")), "CI_FAILED");
                        if (!latest(repo, sha)) { System.out.println("SKIP: Newer main commit exists"); return; }
                        Files.writeString(Path.of(Objects.requireNonNull(System.getenv("GITHUB_OUTPUT"))), "deploy=true\nsha=" + sha + "\n", StandardOpenOption.APPEND);
                        System.out.println("CI PASS: " + sha); return;
                    }
                }
                require(!promotion && attempt < 29, "CI_OR_STAGING_NOT_VERIFIED"); Thread.sleep(15_000);
            }
        } catch (Exception e) { System.err.println("BLOCKED: RELEASE_GATE_FAILED"); System.exit(1); }
    }
}
