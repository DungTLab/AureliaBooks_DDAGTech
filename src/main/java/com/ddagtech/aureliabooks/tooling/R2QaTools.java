package com.ddagtech.aureliabooks.tooling;

import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.ddagtech.aureliabooks.tooling.ToolJson.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Explicit opt-in local QA infrastructure. No provider clients or application datasource. */
public final class R2QaTools {
    private static final Path TARGET = Path.of("target"), STATE = TARGET.resolve("r2-qa-proxy.json");
    private static final String NAME = "aureliabooks-r2-e2e-proxy";
    public static final String IMAGE = "nginx@sha256:a8b39bd9cf0f83869a2162827a0caf6137ddf759d50a171451b335cecc87d236";
    public static URI localQaUri(Object raw) {
        require(raw instanceof String, "INVALID_QA_URL"); URI uri = URI.create((String)raw);
        require("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0 && uri.getPort() <= 65535
                && "/qa".equals(uri.getPath()) && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null, "NONLOCAL_QA_URL");
        return uri;
    }
    public static String qaPassword(Path path) throws Exception {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS), "INVALID_QA_CREDENTIAL_FILE");
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('='); require(equals > 0, "INVALID_QA_CREDENTIAL_LINE");
            require(values.putIfAbsent(line.substring(0, equals), line.substring(equals + 1)) == null, "DUPLICATE_QA_SETTING");
        }
        require("r2_image_qa".equals(values.get("MYSQL_DATABASE")) && "r2_qa".equals(values.get("MYSQL_USER")), "QA_DATABASE_GUARD");
        String password = values.get("MYSQL_PASSWORD"); require(password != null && !password.isEmpty(), "QA_PASSWORD_REQUIRED"); return password;
    }
    private static Map<String, Object> read(Path path) throws Exception {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS), "INVALID_QA_METADATA"); return object(parse(Files.readString(path)));
    }
    private static String docker(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker")); command.addAll(List.of(args));
        return ToolProcess.capture(command, Duration.ofSeconds(45)).strip();
    }
    private static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    public static String nginxConfig(URI primary, URI paused, int port, int pausedPort) {
        localQaUri(primary.toString()); localQaUri(paused.toString());
        require(port > 0 && port <= 65535 && pausedPort > 0 && pausedPort <= 65535 && port != pausedPort, "INVALID_PROXY_PORTS");
        StringBuilder config = new StringBuilder("pid /tmp/nginx.pid;\nerror_log /dev/stderr warn;\nevents {}\nhttp { access_log off; client_body_temp_path /tmp/body;\n");
        for (int i = 0; i < 2; i++) config.append("server { listen 127.0.0.1:").append(i == 0 ? port : pausedPort)
                .append("; client_max_body_size 6m; client_body_timeout 10s; location /qa/ { proxy_set_header Host $http_host; proxy_set_header X-Forwarded-Proto $scheme; proxy_set_header X-Forwarded-For $remote_addr; proxy_read_timeout 25s; proxy_pass http://127.0.0.1:")
                .append(i == 0 ? primary.getPort() : paused.getPort()).append("; } }\n");
        return config.append("}\n").toString();
    }
    private static void start() throws Exception {
        require(!Files.exists(STATE, NOFOLLOW_LINKS), "EXISTING_QA_PROXY"); var qa = read(TARGET.resolve("r2-persistence-qa.json"));
        int first = freePort(), second; do { second = freePort(); } while (first == second);
        Path config = TARGET.resolve("r2-qa-nginx.conf").toAbsolutePath(); require(!Files.isSymbolicLink(config), "UNSAFE_QA_CONFIG");
        Files.writeString(config, nginxConfig(localQaUri(qa.get("url")), localQaUri(qa.get("pausedUrl")), first, second));
        String id = docker("run", "-d", "--rm", "--read-only", "--network", "host", "--name", NAME,
                "--label", "aureliabooks.qa=r2-proxy", "--tmpfs", "/tmp", "--tmpfs", "/var/cache/nginx", "--tmpfs", "/var/run",
                "--mount", "type=bind,source=" + config + ",target=/etc/nginx/nginx.conf,readonly", IMAGE);
        require(id.matches("[a-f0-9]{64}"), "INVALID_CONTAINER_ID");
        Files.writeString(STATE, write(Map.of("container", id, "image", IMAGE, "url", "http://127.0.0.1:" + first + "/qa", "pausedUrl", "http://127.0.0.1:" + second + "/qa")), StandardOpenOption.CREATE_NEW);
        docker("exec", id, "nginx", "-t"); System.out.println("QA_PROXY_STARTED");
    }
    private static void stop() throws Exception {
        var state = read(STATE); Object id = state.get("container"); require(id instanceof String s && s.matches("[a-f0-9]{64}"), "INVALID_CONTAINER_ID");
        require(("/" + NAME).equals(docker("inspect", "--format", "{{.Name}}", (String)id)), "QA_CONTAINER_NAME_MISMATCH");
        require("r2-proxy".equals(docker("inspect", "--format", "{{index .Config.Labels \"aureliabooks.qa\"}}", (String)id)), "QA_CONTAINER_LABEL_MISMATCH");
        docker("stop", (String)id); Files.delete(STATE); System.out.println("QA_PROXY_STOPPED");
    }
    private static boolean http() throws Exception {
        Process process = new ProcessBuilder("./mvnw", "-B", "-ntp", "-Dr2.http.qa=true", "-Dtest=R2HttpEndToEndTest", "test").inheritIO().start();
        try {
            require(process.waitFor(8, TimeUnit.MINUTES), "HTTP_QA_TIMEOUT"); return process.exitValue() == 0;
        } finally {
            if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }
    private static void all() throws Exception {
        Path metadata = TARGET.resolve("r2-persistence-qa.json");
        require(!Files.exists(metadata, NOFOLLOW_LINKS) && !Files.exists(STATE, NOFOLLOW_LINKS), "EXISTING_QA_RUN");
        Files.createDirectories(TARGET); Path log = TARGET.resolve("r2-persistence-server.log");
        require(!Files.isSymbolicLink(log), "UNSAFE_QA_LOG");
        Process server = new ProcessBuilder("bash", "scripts/start-r2-persistence-qa.sh").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean passed = false, cleanupPassed = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!Files.exists(metadata, NOFOLLOW_LINKS)) {
                require(server.isAlive() && System.nanoTime() < deadline, "QA_BOOTSTRAP_FAILED_OR_TIMED_OUT"); Thread.sleep(250);
            }
            start(); passed = http();
        } finally {
            try {
                Path signal = TARGET.resolve("r2-persistence-qa.stop"); require(!Files.isSymbolicLink(signal), "UNSAFE_QA_STOP"); Files.writeString(signal, "");
                if (!server.waitFor(60, TimeUnit.SECONDS)) { server.destroy(); if (!server.waitFor(10, TimeUnit.SECONDS)) server.destroyForcibly(); }
                else cleanupPassed = server.exitValue() == 0;
            } finally { if (Files.exists(STATE, NOFOLLOW_LINKS)) stop(); }
            var cleanup = read(TARGET.resolve("r2-persistence-cleanup.json"));
            cleanupPassed &= Objects.equals(cleanup.get("productsBefore"), cleanup.get("productsAfter"))
                    && new java.math.BigDecimal("0").equals(cleanup.get("remainingRunProducts"));
        }
        require(passed && cleanupPassed, "QA_CHECKS_OR_CLEANUP_FAILED");
        System.out.println("QA_HTTP_AND_CLEANUP_PASS");
    }
    public static void main(String[] args) {
        try {
            require(args.length == 1, "QA_ACTION_REQUIRED");
            switch (args[0]) {
                case "password" -> System.out.print(qaPassword(Path.of(System.getProperty("user.home"), ".local/state/aureliabooks-r2-qa/mysql.env")));
                case "proxy-start" -> start(); case "proxy-stop" -> stop(); case "http" -> require(http(), "HTTP_QA_FAILED"); case "all" -> all();
                default -> throw new IllegalArgumentException("INVALID_QA_ACTION");
            }
        } catch (Exception e) { System.err.println("BLOCKED: LOCAL_QA_OPERATION_FAILED"); System.exit(1); }
    }
}
