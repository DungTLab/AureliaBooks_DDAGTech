package com.ddagtech.aureliabooks.runtimeconfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;

/** Pure overlay renderer for later reviewed activation, not a deployment launcher. */
public final class StagingConfigPlan {
    private StagingConfigPlan() {}
    public static String overlay(String generation, int readGid, boolean enableGoogle) {
        return overlay(generation, readGid, enableGoogle, false);
    }
    /** Fixed, separately reviewed staging choices; never profile names from a payload. */
    public static String overlay(String generation, int readGid, boolean enableGoogle, boolean enableSmsDemo) {
        ConfigPolicy.generation(generation, "0".repeat(40));
        check(readGid > 0, "INVALID_READ_GID");
        try {
            String body = Files.readString(Path.of("deployment/runtime-config/compose.runtime.template.yml"))
                    .replace("__GENERATION__", generation).replace("__APPROVED_READ_GID__", String.valueOf(readGid))
                    .replace("__IMMUTABLE_RELEASE_DIRECTORY__", "/var/lib/aureliabooks-runtime/staging/releases/" + generation);
            String profiles = enableGoogle ? "google" : "";
            if (enableSmsDemo) profiles += profiles.isEmpty() ? "sms-demo" : ",sms-demo";
            if (!profiles.isEmpty()) body = body.replace("      R2_ENABLED: \"false\"",
                    "      SPRING_PROFILES_ACTIVE: \"" + profiles + "\"\n      R2_ENABLED: \"false\"");
            return body;
        } catch (IOException e) { throw new Rejected("TEMPLATE_UNAVAILABLE"); }
    }
}
