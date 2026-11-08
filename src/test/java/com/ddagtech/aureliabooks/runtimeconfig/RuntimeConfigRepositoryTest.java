package com.ddagtech.aureliabooks.runtimeconfig;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigRepositoryTest {
    @Test void deploymentContractsProtectImageOwnershipWithoutFreezingLegitimateFutureChanges() throws Exception {
        // Historical hashes are review evidence, not a permanent ban on teammate changes.
        String automatic = Files.readString(Path.of(".github/workflows/deploy-auto.yml"));
        String promotion = Files.readString(Path.of(".github/workflows/promote-production.yml"));
        String production = Files.readString(Path.of(".github/workflows/deploy-production.yml"));
        assertTrue(automatic.contains("group: aureliabooks-staging-deploy"));
        assertTrue(automatic.contains("environment: staging"));
        assertTrue(automatic.contains("/opt/apps/swp391-staging/scripts/deploy-staging.sh $DEPLOY_SHA"));
        for (String workflow : List.of(promotion, production)) {
            assertTrue(workflow.contains("group: aureliabooks-production-deploy"));
            assertTrue(workflow.contains("environment: production"));
            assertTrue(workflow.contains("/opt/apps/swp391/scripts/deploy.sh $DEPLOY_SHA"));
        }
    }
    @Test void manualWorkflowRequiresExplicitStageOrActivationAndEnvironmentApproval() throws Exception {
        String body = Files.readString(Path.of(".github/workflows/runtime-config-sync.yml"));
        for (String forbidden : List.of("upload-artifact", "python3")) assertFalse(body.contains(forbidden));
        assertTrue(body.contains("refs/heads/main"));
        Map<?, ?> workflow = new Yaml().load(body);
        Map<?, ?> triggers = (Map<?, ?>)workflow.getOrDefault(Boolean.TRUE, null);
        if (triggers == null) triggers = (Map<?, ?>)workflow.get("on");
        assertEquals(Set.of("workflow_dispatch"), triggers.keySet());
        Map<?, ?> jobs = (Map<?, ?>)workflow.get("jobs");
        assertEquals(Set.of("offline-validation", "stage-snapshot"), jobs.keySet());
        Map<?, ?> stage = (Map<?, ?>)jobs.get("stage-snapshot");
        assertEquals("${{ inputs.target_environment }}", stage.get("environment"));
        assertTrue(stage.get("if").toString().contains("inputs.operation == 'stage'"));
        assertTrue(stage.get("if").toString().contains("inputs.operation == 'activate-google'"));
        assertTrue(body.contains("options: [validate, stage, activate-google]"));
        assertTrue(body.contains("default: validate"));
        assertTrue(stage.get("if").toString().contains("refs/heads/main"));
        assertEquals("offline-validation", stage.get("needs"));
        assertFalse(jobs.get("offline-validation").toString().contains("secrets."));
        assertFalse(body.contains("compose up"));
        assertTrue(((Map<?, ?>)stage.get("concurrency")).get("group").toString().startsWith("aureliabooks-runtime-stage-"));
        Map<?, ?> transfer = ((List<?>)stage.get("steps")).stream().map(step -> (Map<?, ?>)step)
                .filter(step -> "Transfer snapshot and perform selected activation".equals(step.get("name")))
                .findFirst().orElseThrow();
        Map<?, ?> environment = (Map<?, ?>)transfer.get("env");
        for (String key : List.of("R2_ENABLED", "R2_UPLOAD_ENABLED"))
            assertEquals("${{ vars." + key + " || 'false' }}", environment.get(key));
        for (String key : List.of("R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY"))
            assertEquals("${{ vars.R2_ENABLED == 'true' && secrets." + key + " || '' }}", environment.get(key));
        for (Object job : jobs.values()) {
            for (Object step : (List<?>)((Map<?, ?>)job).get("steps")) {
                Object uses = ((Map<?, ?>)step).get("uses");
                if (uses != null) assertTrue(uses.toString().matches("actions/[a-z-]+@[a-f0-9]{40}"), "UNPINNED_ACTION");
            }
        }
    }
    @Test void overlayIsStrictlyAdditive() throws Exception {
        String body = Files.readString(Path.of("deployment/runtime-config/compose.runtime.template.yml"));
        for (String forbidden : List.of("image:", "mysql-db:", "DB_PASS", "APP_IMAGE", "entrypoint:", "command:", "ports:")) assertFalse(body.contains(forbidden));
        for (String required : List.of("R2_ENABLED: \"false\"", "R2_UPLOAD_ENABLED: \"false\"", "read_only: true", "create_host_path: false", "__IMMUTABLE_RELEASE_DIRECTORY__")) assertTrue(body.contains(required));
    }
    @Test void noConfigurationSyncPythonFilesRemain() throws Exception {
        try (var paths = Files.walk(Path.of("scripts/runtime-config"))) {
            assertFalse(paths.anyMatch(p -> p.toString().endsWith(".py")));
        }
    }
    @Test void productionCoreHasNoSpringOrProviderOrEnvironmentDiscovery() throws Exception {
        try (var files = Files.list(Path.of("src/main/java/com/ddagtech/aureliabooks/runtimeconfig"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                boolean cli = path.getFileName().toString().equals("RuntimeConfigCli.java");
                for (String forbidden : List.of("org.springframework", "software.amazon", "System.getenv", "java.net.http", "java.net.Socket", "@Component"))
                    if (!(cli && forbidden.equals("System.getenv"))) assertFalse(source.contains(forbidden), "NON_STANDALONE_DEPENDENCY_OR_DISCOVERY");
                if (cli) assertFalse(source.contains("System.getenv()"), "BROAD_ENVIRONMENT_DISCOVERY");
            }
        }
    }
    @Test void offlineFakeApplicationIsNotPackagedInProductionSources() {
        assertFalse(Files.exists(Path.of("src/main/java/com/ddagtech/aureliabooks/runtimeconfig/OfflineConfigStore.java")));
        assertTrue(Files.exists(Path.of("src/test/java/com/ddagtech/aureliabooks/runtimeconfig/OfflineConfigStore.java")));
    }
}
