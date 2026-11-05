package com.ddagtech.aureliabooks.qa;

import com.ddagtech.aureliabooks.tooling.R2QaTools;
import com.ddagtech.aureliabooks.tooling.ToolJson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in port of the 28 Python HTTP checks. Only the guarded loopback QA harness. */
@EnabledIfSystemProperty(named = "r2.http.qa", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class R2HttpEndToEndTest {
    Map<String, Object> qa, proxy;
    String base, paused, code, isbn, bookUrl, penUrl, replacement, bookId, penId;
    Client writer, control, expiry, quota;
    byte[] png;
    int testsRun;
    List<String> failed = new ArrayList<>();
    @RegisterExtension final TestWatcher results = new TestWatcher() {
        @Override public void testSuccessful(ExtensionContext context) { testsRun++; }
        @Override public void testFailed(ExtensionContext context, Throwable cause) { testsRun++; failed.add(context.getDisplayName()); }
        @Override public void testAborted(ExtensionContext context, Throwable cause) { testsRun++; failed.add(context.getDisplayName()); }
    };
    record Response(int status, HttpHeaders headers, byte[] body) {
        String text() { return new String(body, StandardCharsets.UTF_8); }
        Map<String, Object> json() { return ToolJson.object(ToolJson.parse(text())); }
        String header(String name) { return headers.firstValue(name).orElse(null); }
    }
    class Client {
        final String origin;
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
        String token;
        Client() { this(base); }
        Client(String origin) { R2QaTools.localQaUri(origin); this.origin = origin; }
        Response request(String path) throws Exception { return request(path, "GET", null, Map.of()); }
        Response request(String path, String method, byte[] body, Map<String, String> headers) throws Exception {
            assertTrue(path.startsWith("/") && !path.startsWith("//"), "QA request must retain fixed origin");
            var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(25))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header); var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), response.headers(), response.body());
        }
        void refresh() throws Exception {
            Response page = request("/products/qa-session"); assertEquals(200, page.status()); token = csrf(page.text());
        }
        Client login(String role) throws Exception {
            String loginCsrf = csrf(request("/internal/login").text());
            assertEquals(302, form("/internal/login", Map.of("username", "qa-" + role + "@example.test", "password", "QaOnly-NotReal-391", "_csrf", loginCsrf)).status());
            refresh(); return this;
        }
        Response form(String path, Map<String, ?> values) throws Exception {
            List<String> pairs = new ArrayList<>(); values.forEach((key, value) -> pairs.add(encode(key) + "=" + encode(value.toString())));
            return request(path, "POST", String.join("&", pairs).getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "application/x-www-form-urlencoded"));
        }
        Response upload() throws Exception { return upload(png, token); }
        Response upload(byte[] data, String csrfToken) throws Exception {
            String boundary = "qa-" + UUID.randomUUID(); var body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"cover.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            body.write(data); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            Map<String, String> headers = new HashMap<>(Map.of("Content-Type", "multipart/form-data; boundary=" + boundary, "Accept", "application/json", "X-Requested-With", "XMLHttpRequest"));
            if (csrfToken != null) headers.put("X-CSRF-TOKEN", csrfToken);
            return request("/manager/products/images", "POST", body.toByteArray(), headers);
        }
        void control(String action, Map<String, ?> extra) throws Exception {
            Map<String, Object> values = new HashMap<>(extra); values.put("action", action); values.put("_csrf", token);
            assertEquals(200, form("/manager/qa-control", values).status());
        }
        Map<String, Object> snapshot() throws Exception { Response response = request("/manager/qa-state"); assertEquals(200, response.status()); return response.json(); }
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static String csrf(String html) {
        var matcher = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"").matcher(html);
        assertTrue(matcher.find(), "QA CSRF token missing"); return matcher.group(1);
    }
    @BeforeAll void setup() throws Exception {
        qa = ToolJson.object(ToolJson.parse(Files.readString(Path.of("target/r2-persistence-qa.json"))));
        proxy = ToolJson.object(ToolJson.parse(Files.readString(Path.of("target/r2-qa-proxy.json"))));
        base = R2QaTools.localQaUri(proxy.get("url")).toString(); paused = R2QaTools.localQaUri(proxy.get("pausedUrl")).toString();
        R2QaTools.localQaUri(qa.get("url"));
        var bytes = new ByteArrayOutputStream(); var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, 0xc23c1a);
        assertTrue(javax.imageio.ImageIO.write(image, "png", bytes)); png = bytes.toByteArray();
        writer = new Client().login("writer"); control = new Client().login("manager"); expiry = new Client().login("expiry"); quota = new Client().login("quota");
        code = "897" + String.format(Locale.ROOT, "%09d", ThreadLocalRandom.current().nextLong(1_000_000_000L)); isbn = newIsbn();
    }
    static String newIsbn() { return "978" + String.format(Locale.ROOT, "%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L)); }
    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/r2-persistence-http-results.json"), ToolJson.write(Map.of("testsRun", testsRun, "passed", testsRun - failed.size(), "failures", failed.size(), "failedTests", failed, "database", "r2_image_qa", "nginxImage", R2QaTools.IMAGE)));
    }
    void image(String url, int status) throws Exception {
        Response response = new Client().request(url); assertEquals(status, response.status()); assertEquals("no-store", response.header("Cache-Control"));
        assertEquals("nosniff", response.header("X-Content-Type-Options")); assertNull(response.header("Location"));
        if (status == 200) { assertTrue(response.header("Content-Type").startsWith("image/png")); assertArrayEquals(new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10}, Arrays.copyOf(response.body(), 8)); }
    }
    Map<String, Object> book(String url, Map<String, ?> extra) {
        Map<String, Object> values = new HashMap<>(Map.of("barcode", code, "title", "R2 SQL QA Book", "categoryId", qa.get("categoryId"), "price", "100000", "originalCost", "60000", "weightGrams", "200", "isbn", isbn, "publisherId", qa.get("publisherId"), "authorIds", qa.get("authorId")));
        values.put("mainImageUrl", url); values.put("_csrf", writer.token); values.putAll(extra); return values;
    }
    Map<String, Object> pen(String url, Map<String, ?> extra) {
        Map<String, Object> values = new HashMap<>(Map.of("barcode", Long.toString(Long.parseLong(code) + 1), "title", "R2 SQL QA Pen", "categoryId", qa.get("categoryId"), "price", "20000", "originalCost", "10000", "brandId", qa.get("brandId"), "mainImageUrl", url, "_csrf", writer.token));
        values.putAll(extra); return values;
    }
    Map<String, Object> row(String barcode) throws Exception {
        var matches = ToolJson.array(control.snapshot().get("products")).stream().map(ToolJson::object).filter(r -> barcode.equals(r.get("barcode"))).toList();
        assertEquals(1, matches.size(), "Expected one committed SQL row"); return matches.getFirst();
    }
    @Test @Order(1) void databaseGuard() throws Exception { assertEquals("r2_image_qa", control.snapshot().get("database")); }
    @Test @Order(2) void anonymousWithValidCsrf() throws Exception { Client client = new Client(); client.refresh(); assertEquals(401, client.upload().status()); }
    @Test @Order(3) void wrongRoles() throws Exception {
        Object before = control.snapshot().get("objectIds");
        for (String role : List.of("admin", "sale", "customer")) { Response response = new Client().login(role).upload(); assertEquals(403, response.status()); assertNull(response.header("Location")); }
        assertEquals(before, control.snapshot().get("objectIds"));
    }
    @Test @Order(4) void missingAndInvalidCsrf() throws Exception {
        for (String token : new String[]{null, "not-a-valid-token"}) { Response response = expiry.upload(png, token); assertEquals(403, response.status()); assertNull(response.header("Location")); }
    }
    @Test @Order(5) void contextPathForms() throws Exception {
        for (String kind : List.of("books", "stationeries")) assertTrue(writer.request("/manager/" + kind + "/new").text().contains("action=\"/qa/manager/" + kind + "\""));
    }
    @Test @Order(6) void canonicalUploadUrl() throws Exception {
        Response response = writer.upload(); assertEquals(201, response.status()); bookUrl = (String)response.json().get("imageUrl"); assertTrue(bookUrl.matches("/products/images/[0-9a-f-]{36}"));
    }
    @Test @Order(7) void orphanBeforeSave() throws Exception { image(bookUrl, 404); }
    @Test @Order(8) void createBookCommit() throws Exception {
        assertEquals(302, writer.form("/manager/books", book(bookUrl, Map.of())).status()); bookId = row(code).get("id").toString();
        assertEquals(bookUrl, row(code).get("main_image_url")); assertEquals(new java.math.BigDecimal("0"), row(code).get("stock_quantity"));
    }
    @Test @Order(9) void bookReadbackAndPng() throws Exception {
        assertTrue(writer.request("/manager/books/" + bookId + "/edit").text().contains("value=\"" + bookUrl + "\"")); image(bookUrl, 200);
    }
    @Test @Order(10) void stationeryCommit() throws Exception {
        Response upload = writer.upload(); assertEquals(201, upload.status()); penUrl = (String)upload.json().get("imageUrl"); image(penUrl, 404);
        assertEquals(302, writer.form("/manager/stationeries", pen(penUrl, Map.of())).status()); var row = row(Long.toString(Long.parseLong(code) + 1)); penId = row.get("id").toString();
        assertEquals(penUrl, row.get("main_image_url")); image(penUrl, 200);
    }
    @Test @Order(11) void bookCommitFailure() throws Exception {
        Response upload = writer.upload(); assertEquals(201, upload.status()); replacement = (String)upload.json().get("imageUrl"); control.control("commit-fail", Map.of("enabled", "true"));
        Response response = writer.form("/manager/books/" + bookId + "/edit", book(replacement, Map.of("id", bookId, "title", "Must roll back")));
        assertEquals(bookUrl, row(code).get("main_image_url")); assertEquals("R2 SQL QA Book", row(code).get("title")); image(replacement, 404); image(bookUrl, 200);
        assertEquals(503, response.status()); assertTrue(response.text().contains("value=\"" + replacement + "\""));
    }
    @Test @Order(12) void stationeryCommitFailure() throws Exception {
        control.control("commit-fail", Map.of("enabled", "true")); Response response = writer.form("/manager/stationeries/" + penId + "/edit", pen(bookUrl, Map.of("id", penId, "title", "Must roll back pen")));
        assertEquals(penUrl, row(Long.toString(Long.parseLong(code) + 1)).get("main_image_url")); assertEquals(503, response.status()); assertTrue(response.text().contains("value=\"" + bookUrl + "\""));
    }
    @Test @Order(13) void bookCreateFailureNoPartialRow() throws Exception {
        Object before = control.snapshot().get("products"); control.control("commit-fail", Map.of("enabled", "true"));
        Response response = writer.form("/manager/books", book(replacement, Map.of("barcode", Long.toString(Long.parseLong(code) + 2), "isbn", newIsbn())));
        assertEquals(before, control.snapshot().get("products")); assertEquals(503, response.status()); assertTrue(response.text().contains("value=\"" + replacement + "\"")); image(replacement, 404);
    }
    @Test @Order(14) void stationeryCreateFailureNoPartialRow() throws Exception {
        Object before = control.snapshot().get("products"); control.control("commit-fail", Map.of("enabled", "true"));
        Response response = writer.form("/manager/stationeries", pen(replacement, Map.of("barcode", Long.toString(Long.parseLong(code) + 3))));
        assertEquals(before, control.snapshot().get("products")); assertEquals(503, response.status()); assertTrue(response.text().contains("value=\"" + replacement + "\""));
    }
    @Test @Order(15) void retryImageCommit() throws Exception {
        assertEquals(302, writer.form("/manager/books/" + bookId + "/edit", book(replacement, Map.of("id", bookId, "title", "R2 SQL QA Book retried"))).status());
        assertEquals(replacement, row(code).get("main_image_url")); image(replacement, 200); image(bookUrl, 404);
    }
    @Test @Order(16) void storageFailurePreservesSql() throws Exception {
        var before = control.snapshot(); control.control("storage-fail", Map.of("enabled", "true"));
        try { assertEquals(503, writer.upload().status()); } finally { control.control("storage-fail", Map.of("enabled", "false")); }
        var after = control.snapshot(); assertEquals(before.get("objectIds"), after.get("objectIds")); assertEquals(before.get("products"), after.get("products"));
    }
    @Test @Order(17) void sharedReferences() throws Exception {
        assertEquals(302, writer.form("/manager/stationeries/" + penId + "/edit", pen(replacement, Map.of("id", penId))).status());
        control.control("active", Map.of("productId", bookId, "enabled", "false")); image(replacement, 200);
        control.control("active", Map.of("productId", penId, "enabled", "false")); image(replacement, 404);
        control.control("active", Map.of("productId", bookId, "enabled", "true")); control.control("active", Map.of("productId", penId, "enabled", "true")); image(replacement, 200);
        assertTrue(ToolJson.array(control.snapshot().get("objectIds")).contains(bookUrl.substring(bookUrl.lastIndexOf('/') + 1)), "Old orphan must remain");
    }
    @Test @Order(18) void listImagesPreserveContext() throws Exception { assertTrue(writer.request("/manager/products?type=BOOK").text().contains("src=\"/qa" + replacement + "\"")); }
    @Test @Order(19) void expiredSession() throws Exception { expiry.control("expire", Map.of()); Thread.sleep(1300); Response response = expiry.upload(); assertTrue(Set.of(401, 403).contains(response.status())); assertNull(response.header("Location")); }
    @Test @Order(20) void oldCsrfAfterLogin() throws Exception {
        Client client = new Client().login("expiry"); String old = client.token; client.form("/internal/logout", Map.of("_csrf", client.token)); client.login("expiry"); assertEquals(403, client.upload(png, old).status());
    }
    @Test @Order(21) void rateLimit() throws Exception {
        for (int i = 0; i < ((Number)qa.get("quota")).intValue(); i++) assertEquals(201, quota.upload().status());
        Response response = quota.upload(); assertEquals(429, response.status()); assertEquals("60", response.header("Retry-After")); assertNull(response.header("Location"));
    }
    @Test @Order(22) void concurrentBound() throws Exception {
        Client first = new Client().login("parallel"), second = new Client().login("parallel"), third = new Client().login("parallel");
        control.control("hold", Map.of("enabled", "true"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Response> a = executor.submit(() -> first.upload()); Future<Response> b = executor.submit(() -> second.upload());
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (((Number)control.snapshot().get("inFlight")).intValue() < 2 && System.nanoTime() < deadline) Thread.sleep(50);
                assertEquals(2, ((Number)control.snapshot().get("inFlight")).intValue()); Response response = third.upload(); assertEquals(429, response.status()); assertEquals("1", response.header("Retry-After"));
            } finally { control.control("hold", Map.of("enabled", "false")); }
            assertEquals(201, a.get(10, TimeUnit.SECONDS).status()); assertEquals(201, b.get(10, TimeUnit.SECONDS).status());
        }
        assertEquals(0, ((Number)control.snapshot().get("inFlight")).intValue());
    }
    Map<String, String> oversized(String origin, boolean chunked) throws Exception {
        Client client = new Client(origin).login("expiry"); URI uri = R2QaTools.localQaUri(origin);
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 15_000); socket.setSoTimeout(15_000);
            var output = socket.getOutputStream(); StringBuilder headers = new StringBuilder("POST /qa/manager/products/images HTTP/1.1\r\nHost: 127.0.0.1:" + uri.getPort() + "\r\nConnection: close\r\n");
            client.cookies.get(URI.create(origin + "/manager/products/images"), Map.of()).forEach((key, values) -> headers.append(key).append(": ").append(String.join("; ", values)).append("\r\n"));
            headers.append("X-CSRF-TOKEN: ").append(client.token).append("\r\nAccept: application/json\r\nX-Requested-With: XMLHttpRequest\r\nContent-Type: multipart/form-data; boundary=qa-size\r\n")
                    .append(chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: 7340032\r\n").append("\r\n");
            output.write(headers.toString().getBytes(StandardCharsets.US_ASCII)); output.flush();
            if (chunked) {
                try {
                    chunk(output, "--qa-size\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.png\"\r\nContent-Type: image/png\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    byte[] data = new byte[1024 * 1024]; for (int i = 0; i < 7; i++) chunk(output, data);
                    chunk(output, "\r\n--qa-size--\r\n".getBytes(StandardCharsets.US_ASCII)); output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); output.flush();
                } catch (IOException ignored) { /* Server may reject before consuming the oversized body. */ }
            }
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String status = input.readLine(); assertNotNull(status); assertEquals("413", status.split(" ")[1]);
            Map<String, String> received = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) { int colon = line.indexOf(':'); assertTrue(colon > 0); received.put(line.substring(0, colon), line.substring(colon + 1).strip()); }
            assertFalse(received.containsKey("Location")); return received;
        }
    }
    static void chunk(OutputStream output, byte[] data) throws Exception { output.write((Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.US_ASCII)); output.write(data); output.write("\r\n".getBytes(StandardCharsets.US_ASCII)); output.flush(); }
    @Test @Order(23) void proxyContentLengthLimit() throws Exception { oversized(base, false); }
    @Test @Order(24) void proxyChunkedLimit() throws Exception { oversized(base, true); }
    @Test @Order(25) void servletChunkedLimit() throws Exception { assertTrue(oversized((String)qa.get("url"), true).getOrDefault("Content-Type", "").contains("application/json")); }
    @Test @Order(26) void uploadPausePreservesReads() throws Exception {
        Client client = new Client(paused).login("manager"); assertFalse(client.request("/manager/books/new").text().contains("data-image-uploader")); assertEquals(503, client.upload().status());
        Response response = new Client(paused).request(replacement); assertEquals(200, response.status()); assertTrue(response.header("Content-Type").startsWith("image/png"));
    }
    @Test @Order(27) void externalUrlRoundTrip() throws Exception {
        String external = "https://example.com/legacy.png?x=1&y=2";
        assertEquals(302, writer.form("/manager/stationeries/" + penId + "/edit", pen(external, Map.of("id", penId))).status()); assertEquals(external, row(Long.toString(Long.parseLong(code) + 1)).get("main_image_url"));
    }
    @Test @Order(28) void noBackendPortInRedirect() throws Exception {
        Response response = new Client().request("/manager/books"); assertEquals(302, response.status()); URI location = URI.create(response.header("Location"));
        assertTrue(location.getPath().endsWith("/qa/internal/login")); assertTrue(location.getRawAuthority() == null || location.getRawAuthority().equals(URI.create(base).getRawAuthority()));
    }
}
