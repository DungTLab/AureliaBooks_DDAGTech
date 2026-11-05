package com.ddagtech.aureliabooks.runtimeconfig;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;

/** Fixed app-only Docker operations. Never pulls images, touches DB, or calls providers. */
final class RuntimeDocker implements RuntimeActivation.Engine {
    private final String environment, app, domain;
    private final Path root, overlay;
    private final int port;
    RuntimeDocker(String environment) {
        check(Set.of("staging", "production").contains(environment), "INVALID_ENVIRONMENT"); this.environment = environment;
        root = Path.of(environment.equals("staging") ? "/opt/apps/swp391-staging" : "/opt/apps/swp391");
        overlay = Path.of("/etc/aureliabooks-runtime", environment, "active.compose.yaml");
        app = "aurelia-" + environment + "-app-1"; port = environment.equals("staging") ? 18080 : 8080;
        domain = environment.equals("staging") ? "aurelia-staging.dzungtlab.id.vn" : "aureliabooks.dzungtlab.id.vn";
    }
    private String command(List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin");
        Process child = builder.start(); child.getOutputStream().close();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = executor.submit(() -> {
                try (var input = child.getInputStream()) { byte[] data = input.readNBytes(8193); check(data.length <= 8192, "DOCKER_OUTPUT_LIMIT"); return data; }
            });
            try {
                check(child.waitFor(45, TimeUnit.SECONDS), "DOCKER_TIMEOUT"); check(child.exitValue() == 0, "DOCKER_OPERATION_FAILED");
                return new String(output.get(2, TimeUnit.SECONDS), java.nio.charset.StandardCharsets.UTF_8).strip();
            } finally {
                child.descendants().forEach(ProcessHandle::destroyForcibly); child.destroyForcibly(); child.getInputStream().close(); output.cancel(true); executor.shutdownNow();
            }
        }
    }
    private String inspect(String template) throws Exception { return command(List.of("/usr/bin/docker", "inspect", "--format", template, app)); }
    private String image() throws Exception { return inspect("{{.Config.Image}}"); }
    private String decision(String key, String value) throws Exception {
        return inspect("{{range .Config.Env}}{{if eq . \"" + key + "=" + value + "\"}}EXPECTED{{end}}{{end}}");
    }
    @Override public String preflight() throws Exception {
        check("appuser".equals(inspect("{{.Config.User}}")), "UNVERIFIED_APP_USER");
        check("EXPECTED_NO_ARGS".equals(inspect("{{if and (eq (len .Config.Entrypoint) 3) (eq (index .Config.Entrypoint 0) \"java\") (eq (index .Config.Entrypoint 1) \"-jar\") (eq (index .Config.Entrypoint 2) \"/app/app.jar\")}}EXPECTED{{end}}{{if not .Config.Cmd}}_NO_ARGS{{end}}")), "UNVERIFIED_JAVA_ARGUMENTS");
        Set<String> keys = new HashSet<>(Arrays.asList(inspect("{{range .Config.Env}}{{println (index (split . \"=\") 0)}}{{end}}").split("\\s+")));
        for (String key : keys) {
            check(!FIELDS.containsKey(key) && !key.startsWith("SPRING_MAIL_") && !key.startsWith("SPRING_SECURITY_OAUTH2_")
                    && !key.startsWith("VNPAY_") && !key.startsWith("GOOGLE_") && !key.startsWith("DEMO_SMS_")
                    && !key.startsWith("APP_R2_") && !key.startsWith("APP_CONTACT"), "PROVIDER_ENVIRONMENT_CONFLICT");
            check(!Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "_JAVA_OPTIONS", "SPRING_APPLICATION_JSON", "SPRING_CONFIG_LOCATION", "SPRING_CONFIG_IMPORT", "SPRING_CONFIG_NAME").contains(key), "CONFIGURATION_OVERRIDE_CONFLICT");
            check(!key.startsWith("SPRING_PROFILES_") || key.equals("SPRING_PROFILES_ACTIVE"), "PROFILE_OVERRIDE_CONFLICT");
        }
        if (keys.contains("SPRING_PROFILES_ACTIVE")) check("EXPECTED".equals(decision("SPRING_PROFILES_ACTIVE", "google")), "PROFILE_CONFLICT");
        if (keys.contains("SPRING_CONFIG_ADDITIONAL_LOCATION")) check("EXPECTED".equals(decision("SPRING_CONFIG_ADDITIONAL_LOCATION", "file:/run/aureliabooks-config/runtime-integrations.properties")), "LOCATION_CONFLICT");
        if (keys.contains("SERVER_FORWARD_HEADERS_STRATEGY")) check("EXPECTED".equals(decision("SERVER_FORWARD_HEADERS_STRATEGY", "framework")), "FORWARD_HEADERS_CONFLICT");
        for (String flag : FLAGS.keySet()) if (keys.contains(flag)) check("EXPECTED".equals(decision(flag, "false")), "R2_FLAG_CONFLICT");
        String image = image(); check(image.matches("ghcr.io/dungtlab/aureliabooks_ddagtech:sha-[a-f0-9]{40}"), "UNEXPECTED_IMAGE"); return image;
    }
    private void compose(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("/usr/bin/docker", "compose", "--project-directory", root.toString(), "-f", root.resolve("compose.yaml").toString()));
        if (environment.equals("production")) command.addAll(List.of("-f", root.resolve("compose.caddy.yaml").toString()));
        if (Files.exists(overlay, LinkOption.NOFOLLOW_LINKS)) command.addAll(List.of("-f", overlay.toString()));
        command.addAll(List.of(args)); command(command);
    }
    @Override public void recreate() throws Exception {
        compose("config", "--quiet"); compose("up", "-d", "--no-deps", "--force-recreate", "--pull", "never", "app");
    }
    private record Probe(int status, String location, String body) {}
    private Probe probe(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("X-Forwarded-Host", domain); connection.setRequestProperty("X-Forwarded-Proto", "https");
        try {
            int status = connection.getResponseCode(); String body = "";
            if (status == 200) try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(131073); check(bytes.length <= 131072, "HTTP_PROBE_LIMIT"); body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            return new Probe(status, connection.getHeaderField("Location"), body);
        } finally { connection.disconnect(); }
    }
    private boolean google() throws Exception {
        Probe login = probe("/auth/login"); if (login.status != 200 || !login.body.contains("href=\"/auth/google/login")) return false;
        Probe authorization = probe("/oauth2/authorization/google");
        if (authorization.status != 302 || authorization.location == null) return false;
        URI destination = URI.create(authorization.location);
        if (!"https".equals(destination.getScheme()) || !"accounts.google.com".equals(destination.getHost())) return false;
        Map<String, String> query = new HashMap<>();
        for (String pair : destination.getRawQuery().split("&")) { String[] pieces = pair.split("=", 2); query.put(URLDecoder.decode(pieces[0], java.nio.charset.StandardCharsets.UTF_8), pieces.length == 2 ? URLDecoder.decode(pieces[1], java.nio.charset.StandardCharsets.UTF_8) : ""); }
        return ("https://" + domain + "/login/oauth2/code/google").equals(query.get("redirect_uri"));
    }
    @Override public boolean ready(String expectedImage, String generation, boolean enableGoogle) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                check(expectedImage.equals(image()), "IMAGE_CHANGED_DURING_ACTIVATION");
                if ("true".equals(inspect("{{.State.Running}}")) && probe("/").status == 200) {
                    if (generation != null) {
                        check(generation.equals(inspect("{{index .Config.Labels \"com.ddagtech.aureliabooks.runtime-config-generation\"}}")), "GENERATION_NOT_LOADED");
                        String mount = inspect("{{range .Mounts}}{{if eq .Destination \"/run/aureliabooks-config\"}}{{if not .RW}}{{.Source}}{{end}}{{end}}{{end}}");
                        check(mount.equals("/var/lib/aureliabooks-runtime/" + environment + "/releases/" + generation), "RUNTIME_MOUNT_CHANGED");
                        check("999".equals(command(List.of("/usr/bin/docker", "exec", app, "id", "-g"))), "APP_GID_CHANGED");
                        command(List.of("/usr/bin/docker", "exec", app, "test", "-r", "/run/aureliabooks-config/runtime-integrations.properties"));
                    }
                    if (!enableGoogle || google()) return true;
                }
            } catch (Exception ignored) { /* Static result only: never return HTTP, child or provider diagnostics. */ }
            Thread.sleep(2000);
        }
        return false;
    }
}
