package com.ddagtech.aureliabooks.runtimeconfig;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import java.util.*;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;
import static com.ddagtech.aureliabooks.runtimeconfig.StagingConfigPlan.*;
import static com.ddagtech.aureliabooks.runtimeconfig.RuntimeConfigFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigStagingPlanTest {
    @SuppressWarnings("unchecked")
    @Test void overlayOnlyChangesAppRuntime() {
        Map<String, Object> document = new Yaml().load(overlay(FIRST, 12345, false));
        assertEquals(Set.of("services"), document.keySet());
        Map<String, Object> services = (Map<String, Object>)document.get("services");
        assertEquals(Set.of("app"), services.keySet());
        Map<String, Object> app = (Map<String, Object>)services.get("app");
        assertEquals(Set.of("environment", "labels", "group_add", "volumes"), app.keySet());
        Map<String, String> environment = (Map<String, String>)app.get("environment");
        assertEquals("false", environment.get("R2_ENABLED")); assertEquals("false", environment.get("R2_UPLOAD_ENABLED"));
        assertFalse(environment.containsKey("SPRING_PROFILES_ACTIVE"));
        var volume = ((List<Map<String, Object>>)app.get("volumes")).getFirst();
        assertEquals(true, volume.get("read_only"));
        assertEquals(false, ((Map<?, ?>)volume.get("bind")).get("create_host_path"));
        assertEquals("/var/lib/aureliabooks-runtime/staging/releases/" + FIRST, volume.get("source"));
    }
    @Test void googleActivationIsExplicit() {
        assertTrue(overlay(FIRST, 12345, true).contains("SPRING_PROFILES_ACTIVE: \"google\""));
        assertFalse(overlay(FIRST, 12345, false).contains("SPRING_PROFILES_ACTIVE"));
    }
    @Test void smsDemoProfileRequiresAnExplicitSeparateDecision() {
        assertFalse(overlay(FIRST, 12345, true).contains("sms-demo"));
        assertFalse(overlay(FIRST, 12345, false, false).contains("SPRING_PROFILES_ACTIVE"));
        assertTrue(overlay(FIRST, 12345, false, true).contains("SPRING_PROFILES_ACTIVE: \"sms-demo\""));
        assertTrue(overlay(FIRST, 12345, true, true).contains("SPRING_PROFILES_ACTIVE: \"google,sms-demo\""));
    }
    @Test void rejectTraversalAndNonApprovedGidShape() {
        assertThrows(Rejected.class, () -> overlay("../production", 12345, false));
        assertThrows(Rejected.class, () -> overlay(FIRST, 0, false));
        assertThrows(Rejected.class, () -> overlay(FIRST, -1, false));
    }
}
