package com.ddagtech.aureliabooks.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static com.ddagtech.aureliabooks.service.impl.R2SmokeProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real AWS SDK marshalling/signing + real processor/storage, entirely in-memory HTTP. */
class R2SmokeProtocolTest {
    @TempDir Path temp;
    static final String REVISION = "a".repeat(40);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    static final String SENTINEL = "OFFLINE_SENTINEL_DO_NOT_LOG";
    static final StaticCredentialsProvider DUMMY = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("OFFLINE_DUMMY", "OFFLINE_DUMMY_SECRET"));

    enum Mode {
        NORMAL, EXISTS, PREFLIGHT_403, PREFLIGHT_BUCKET_404, PREFLIGHT_GENERIC_404,
        PUT_TIMEOUT_CREATED, PUT_TIMEOUT_ABSENT, PUT_TIMEOUT_LATE_COMMIT, PUT_CONFLICT,
        PUT_CONFLICT_MATCHING_MARKER, PUT_500, PUT_500_CREATED,
        FOREIGN_MARKER, BAD_HASH, BAD_MIME, BAD_LENGTH, REPLACED_BEFORE_DELETE,
        DELETE_KEEPS_OBJECT, DELETE_TIMEOUT_GONE, DELETE_TIMEOUT_REMAINS, ABSENCE_403
    }

    Plan approved() throws IOException {
        byte[] png = new ProductImageProcessor().toPng(multipart(fixture()));
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        return new Plan(REVISION, id, "0".repeat(32),
                "https://" + "0".repeat(32) + ".r2.cloudflarestorage.com", BUCKET,
                "DEFAULT", sha256(png), png.length, "products/" + id + ".png",
                CLOCK.instant().plusSeconds(3600), true, true, true, true, true, true);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void protocolHandlesSuccessAndUncertainOutcomesWithoutNetwork(Mode mode) throws Exception {
        Plan plan = approved();
        Path journal = temp.resolve("journal.jsonl");
        FakeHttp http = new FakeHttp(mode, plan, journal);
        Result result = run(plan, REVISION, CLOCK, fixture(), journal,
                p -> sdkBuilder(p, REVISION, CLOCK, DUMMY).httpClient(http).build());
        Outcome expected = switch (mode) {
            case NORMAL -> Outcome.PASS;
            case EXISTS -> Outcome.PREEXISTING_OBJECT;
            case PREFLIGHT_403, PREFLIGHT_BUCKET_404, PREFLIGHT_GENERIC_404 -> Outcome.PREFLIGHT_UNVERIFIED;
            case PUT_TIMEOUT_CREATED, PUT_500_CREATED -> Outcome.PUT_UNCERTAIN_CLEANED;
            case PUT_CONFLICT, PUT_CONFLICT_MATCHING_MARKER -> Outcome.PUT_REJECTED;
            case DELETE_TIMEOUT_GONE -> Outcome.DELETE_UNCERTAIN_ABSENT;
            case DELETE_KEEPS_OBJECT, DELETE_TIMEOUT_REMAINS, ABSENCE_403 -> Outcome.CLEANUP_UNVERIFIED;
            default -> Outcome.OWNERSHIP_UNVERIFIED;
        };
        assertEquals(expected, result.outcome());
        assertEquals(plan.key(), result.key());
        boolean cleaned = EnumSet.of(Mode.NORMAL, Mode.PUT_TIMEOUT_CREATED, Mode.PUT_500_CREATED,
                Mode.DELETE_TIMEOUT_GONE).contains(mode);
        assertEquals(cleaned, result.absenceVerified());
        assertTrue(http.puts <= 1, "SDK must not retry even an HTTP 500 or an IOException");
        assertTrue(http.deletes <= 1);
        assertTrue(http.gets <= 4);
        if (EnumSet.of(Mode.EXISTS, Mode.PREFLIGHT_403, Mode.PREFLIGHT_BUCKET_404,
                Mode.PREFLIGHT_GENERIC_404).contains(mode)) {
            assertEquals(0, http.puts);
        } else { assertEquals(1, http.puts); }
        if (EnumSet.of(Mode.NORMAL, Mode.PUT_TIMEOUT_CREATED, Mode.PUT_500_CREATED, Mode.DELETE_KEEPS_OBJECT,
                Mode.DELETE_TIMEOUT_GONE, Mode.DELETE_TIMEOUT_REMAINS, Mode.ABSENCE_403).contains(mode)) {
            assertEquals(1, http.deletes);
        } else { assertEquals(0, http.deletes); }
        if (mode == Mode.PUT_TIMEOUT_LATE_COMMIT) {
            assertNotNull(http.object, "Negative read must not be treated as proof of no late commit");
        }
        String evidence = Files.readString(journal);
        assertTrue(evidence.contains(plan.key()));
        assertTrue(evidence.contains(plan.pngSha256()));
        assertTrue(evidence.contains(expected.name()));
        assertFalse(evidence.contains(SENTINEL));
        assertFalse(evidence.contains("OFFLINE_DUMMY_SECRET"));
        assertFalse(evidence.contains("Authorization"));
    }

    @Test void springSelectsProductionConstructorWithoutApplicationOrDatasource() throws Exception {
        Plan plan = approved();
        FakeHttp http = new FakeHttp(Mode.NORMAL, plan, temp.resolve("unused"));
        new ApplicationContextRunner()
                .withBean(S3Client.class, () -> sdkBuilder(plan, REVISION, CLOCK, DUMMY).httpClient(http).build())
                .withBean(ProductImageProcessor.class)
                .withUserConfiguration(R2ProductImageStorage.class)
                .withPropertyValues("app.r2.enabled=true", "app.r2.bucket=" + BUCKET)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(R2ProductImageStorage.class));
                    assertEquals(0, http.gets + http.puts + http.deletes);
                });
    }

    @Test void draftIsNonExecutableAndCannotBeOverwritten() throws Exception {
        Path directory = temp.resolve("draft");
        Plan draft = prepare(directory, REVISION);
        assertEquals(4, draft.imageId().version());
        assertEquals(2, draft.imageId().variant());
        byte[] png = Files.readAllBytes(directory.resolve("normalized.png"));
        assertEquals(draft.pngSha256(), sha256(png));
        assertNotEquals(draft.pngSha256(), sha256(Files.readAllBytes(directory.resolve("source.jpg"))));
        Plan reread = JSON.readValue(directory.resolve("approval-draft.json").toFile(), Plan.class);
        assertEquals(draft, reread);
        assertThrows(IllegalArgumentException.class, () -> reread.validate(REVISION, CLOCK));
        assertThrows(FileAlreadyExistsException.class, () -> prepare(directory, REVISION));
        assertEquals(draft, JSON.readValue(directory.resolve("approval-draft.json").toFile(), Plan.class));
    }

    @Test void allUnconfirmedOrAlteredApprovalFieldsBlockBeforeFactoryOrJournal() throws Exception {
        Plan plan = approved();
        Map<String, Object> original = JSON.convertValue(plan, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("sourceRevision", "b".repeat(40));
        changes.put("imageId", "550e8400-e29b-11d4-a716-446655440000");
        changes.put("accountId", "1".repeat(32));
        changes.put("bucket", "production-assets");
        changes.put("jurisdiction", "EU");
        changes.put("approvedDeleteKey", "products/some-other-key.png");
        changes.put("approvalExpiresAt", "2026-10-31T00:00:00Z");
        changes.put("pngSha256", "0".repeat(64));
        changes.put("pngBytes", 1);
        for (String flag : List.of("executionApproved", "bucketIdentityAndPrivacyVerified",
                "lifecycleAndExclusiveWindowVerified", "scopedObjectReadWriteTokenVerified",
                "tokenStatusAndExpiryVerified", "secretInjectionAndRotationVerified")) {
            changes.put(flag, false);
        }
        int i = 0;
        for (var change : changes.entrySet()) {
            Map<String, Object> modified = new LinkedHashMap<>(original);
            modified.put(change.getKey(), change.getValue());
            Plan invalid = JSON.convertValue(modified, Plan.class);
            Path journal = temp.resolve("blocked-" + i++);
            assertThrows(IllegalArgumentException.class, () -> run(invalid, REVISION, CLOCK,
                    fixture(), journal, p -> { fail("Must not construct a client"); return null; }),
                    change.getKey());
            assertFalse(Files.exists(journal));
        }
        for (String endpoint : List.of(plan.endpoint() + "/", plan.endpoint() + "/bucket",
                plan.endpoint() + ":443", plan.endpoint() + "?x=y", plan.endpoint() + "#fragment",
                plan.endpoint().replace("https://", "https://user:secret@"),
                plan.endpoint().replace("https:", "http:"), "https://example.com")) {
            var modified = new LinkedHashMap<>(original);
            modified.put("endpoint", endpoint);
            assertThrows(IllegalArgumentException.class, () ->
                    JSON.convertValue(modified, Plan.class).validate(REVISION, CLOCK));
        }
    }

    @Test void existingJournalBlocksBeforeClientCreation() throws Exception {
        Path journal = temp.resolve("used.jsonl");
        Files.writeString(journal, "retained evidence");
        assertThrows(FileAlreadyExistsException.class, () -> run(approved(), REVISION, CLOCK,
                fixture(), journal, p -> { fail("Must not construct a client"); return null; }));
        assertEquals("retained evidence", Files.readString(journal));
    }

    enum ClientPolicy { RETRIES, ENDPOINT, REGION, TIMEOUT, ATTEMPT_TIMEOUT, CREDENTIALS }

    @SuppressWarnings("deprecation")
    @ParameterizedTest @EnumSource(ClientPolicy.class)
    void sdkPolicyMustMatchApprovalBeforeFirstRequest(ClientPolicy policy) throws Exception {
        Plan plan = approved();
        Path journal = temp.resolve("retry.jsonl");
        FakeHttp http = new FakeHttp(Mode.NORMAL, plan, journal);
        Result result = run(plan, REVISION, CLOCK, fixture(), journal,
                p -> {
                    var builder = sdkBuilder(p, REVISION, CLOCK, DUMMY).httpClient(http);
                    switch (policy) {
                        case RETRIES -> builder.overrideConfiguration(c -> c.retryPolicy(RetryPolicy.defaultRetryPolicy()));
                        case ENDPOINT -> builder.endpointOverride(java.net.URI.create("https://example.com"));
                        case REGION -> builder.region(software.amazon.awssdk.regions.Region.US_EAST_1);
                        case TIMEOUT -> builder.overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60)));
                        case ATTEMPT_TIMEOUT -> builder.overrideConfiguration(c -> c.apiCallAttemptTimeout(Duration.ofSeconds(20)));
                        case CREDENTIALS -> builder.credentialsProvider(AnonymousCredentialsProvider.create());
                    }
                    return builder.build();
                });
        assertEquals(Outcome.CLIENT_POLICY_BLOCKED, result.outcome());
        assertEquals(0, http.gets + http.puts + http.deletes);
    }

    @Test void journalFailureOrExpiredApprovalCannotSendPut() throws Exception {
        Plan plan = approved();
        Path path = temp.resolve("io-failure.jsonl");
        FakeHttp http = new FakeHttp(Mode.NORMAL, plan, path);
        try (S3Client sdk = sdkBuilder(plan, REVISION, CLOCK, DUMMY).httpClient(http).build()) {
            Journal closed = new Journal(path);
            Guard guard = new Guard(plan, closed, sdk, CLOCK);
            guard.phase = Phase.STORE;
            closed.close();
            var put = PutObjectRequest.builder().bucket(BUCKET).key(plan.key()).contentType("image/png").build();
            byte[] png = new ProductImageProcessor().toPng(multipart(fixture()));
            assertThrows(UncheckedIOException.class, () -> guard.client().putObject(put, RequestBody.fromBytes(png)));
            assertFalse(guard.putAttempted);
            try (Journal journal = new Journal(temp.resolve("expired.jsonl"))) {
                Guard expired = new Guard(plan, journal, sdk, Clock.offset(CLOCK, Duration.ofHours(2)));
                expired.phase = Phase.STORE;
                assertThrows(IllegalArgumentException.class, () ->
                        expired.client().putObject(put, RequestBody.fromBytes(png)));
            }
            assertEquals(0, http.gets + http.puts + http.deletes);
        }
    }

    @Test void guardRejectsOtherKeysBucketsBulkListingBadBytesAndRepeatedCalls() throws Exception {
        Plan plan = approved();
        Path path = temp.resolve("guard.jsonl");
        FakeHttp http = new FakeHttp(Mode.NORMAL, plan, path);
        try (Journal journal = new Journal(path);
             S3Client sdk = sdkBuilder(plan, REVISION, CLOCK, DUMMY).httpClient(http).build()) {
            journal.append(Map.of("event", "PLAN", "plan", plan));
            Guard guard = new Guard(plan, journal, sdk, CLOCK);
            S3Client client = guard.client();
            assertThrows(SecurityException.class, client::listBuckets);
            assertThrows(SecurityException.class, () -> client.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(BUCKET).build()));
            assertThrows(SecurityException.class, () -> client.deleteObjects(
                    DeleteObjectsRequest.builder().bucket(BUCKET).build()));
            assertThrows(IllegalArgumentException.class, () -> client.getObject(
                    GetObjectRequest.builder().bucket("other").key(plan.key()).build()));
            assertThrows(IllegalArgumentException.class, () -> client.getObject(
                    GetObjectRequest.builder().bucket(BUCKET).key("products/other.png").build()));
            guard.phase = Phase.STORE;
            assertThrows(IllegalArgumentException.class, () -> client.putObject(
                    PutObjectRequest.builder().bucket(BUCKET).key(plan.key()).contentType("image/png").build(),
                    RequestBody.fromBytes(new byte[]{1, 2, 3})));
            assertEquals(0, http.gets + http.puts + http.deletes);
            byte[] png = new ProductImageProcessor().toPng(multipart(fixture()));
            var request = PutObjectRequest.builder().bucket(BUCKET).key(plan.key()).contentType("image/png").build();
            client.putObject(request, RequestBody.fromBytes(png));
            assertThrows(SecurityException.class, () -> client.putObject(request, RequestBody.fromBytes(png)));
            assertEquals(1, http.puts);
        }
    }

    @Test void invalidInjectedIdCannotReachS3() throws Exception {
        Plan plan = approved();
        FakeHttp http = new FakeHttp(Mode.NORMAL, plan, temp.resolve("unused"));
        try (S3Client client = sdkBuilder(plan, REVISION, CLOCK, DUMMY).httpClient(http).build()) {
            var storage = new R2ProductImageStorage(client, new ProductImageProcessor(), BUCKET,
                    () -> UUID.fromString("550e8400-e29b-11d4-a716-446655440000"));
            assertThrows(IllegalStateException.class, () -> storage.store(multipart(fixture())));
            assertEquals(0, http.gets + http.puts + http.deletes);
        }
    }

    /** No socket/DNS implementation exists here. Actual SDK requests end in this method. */
    static final class FakeHttp implements SdkHttpClient {
        final Mode mode;
        final Plan plan;
        final Path journal;
        byte[] object;
        Map<String, String> metadata = new HashMap<>();
        int gets, puts, deletes;
        FakeHttp(Mode mode, Plan plan, Path journal) {
            this.mode = mode; this.plan = plan; this.journal = journal;
            if (mode == Mode.EXISTS) { object = new byte[]{42}; }
        }
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
                public void abort() {}
                public HttpExecuteResponse call() throws IOException {
                    assertEquals("https", request.httpRequest().protocol());
                    assertEquals(java.net.URI.create(plan.endpoint()).getHost(), request.httpRequest().host());
                    assertEquals("/" + BUCKET + "/" + plan.key(), request.httpRequest().encodedPath());
                    String evidence = Files.readString(journal);
                    assertTrue(evidence.contains(plan.key()), "Key must already be on disk");
                    assertTrue(evidence.contains(plan.pngSha256()), "PNG hash must already be on disk");
                    return switch (request.httpRequest().method()) {
                        case GET -> get(evidence);
                        case PUT -> put(request, evidence);
                        case DELETE -> delete(evidence);
                        default -> throw new AssertionError("Disallowed SDK HTTP method");
                    };
                }
            };
        }
        HttpExecuteResponse put(HttpExecuteRequest request, String evidence) throws IOException {
            puts++;
            assertTrue(evidence.contains("BEFORE_STORE"));
            assertEquals("*", request.httpRequest().firstMatchingHeader("If-None-Match").orElseThrow());
            byte[] bytes;
            try (InputStream stream = request.contentStreamProvider().orElseThrow().newStream()) {
                bytes = stream.readAllBytes();
            }
            // This is the wire PUT body from the real SDK, not just RequestBody input.
            assertEquals(plan.pngSha256(), sha256(bytes));
            assertEquals(plan.pngBytes(), bytes.length);
            if (mode == Mode.PUT_TIMEOUT_ABSENT || mode == Mode.PUT_TIMEOUT_LATE_COMMIT) {
                throw new IOException(SENTINEL);
            }
            if (mode == Mode.PUT_500) { return error(500, "InternalError"); }
            object = bytes;
            metadata.put("x-amz-meta-smoke-run", request.httpRequest()
                    .firstMatchingHeader("x-amz-meta-smoke-run").orElseThrow());
            metadata.put("x-amz-meta-smoke-sha256", request.httpRequest()
                    .firstMatchingHeader("x-amz-meta-smoke-sha256").orElseThrow());
            if (mode == Mode.PUT_CONFLICT || mode == Mode.FOREIGN_MARKER) {
                metadata.put("x-amz-meta-smoke-run", "foreign");
            }
            if (mode == Mode.PUT_CONFLICT || mode == Mode.PUT_CONFLICT_MATCHING_MARKER) {
                return error(412, "PreconditionFailed");
            }
            if (mode == Mode.PUT_TIMEOUT_CREATED) { throw new IOException(SENTINEL); }
            if (mode == Mode.PUT_500_CREATED) { return error(500, "InternalError"); }
            return response(200, new byte[0], Map.of("ETag", "\"this-is-not-sha256\""));
        }
        HttpExecuteResponse get(String evidence) throws IOException {
            gets++;
            if (gets == 1) {
                assertTrue(evidence.contains("BEFORE_PREFLIGHT"));
                if (mode == Mode.PREFLIGHT_403) { return error(403, "AccessDenied"); }
                if (mode == Mode.PREFLIGHT_BUCKET_404) { return error(404, "NoSuchBucket"); }
                if (mode == Mode.PREFLIGHT_GENERIC_404) { return error(404, "NotFound"); }
            }
            if (mode == Mode.ABSENCE_403 && deletes > 0) { return error(403, "AccessDenied"); }
            if (object == null) {
                HttpExecuteResponse missing = error(404, "NoSuchKey");
                if (mode == Mode.PUT_TIMEOUT_LATE_COMMIT && puts == 1) {
                    object = new byte[]{42}; // An in-flight PUT commits after a negative read.
                }
                return missing;
            }
            if (mode == Mode.REPLACED_BEFORE_DELETE && gets == 3) {
                metadata.put("x-amz-meta-smoke-run", "other-owner");
            }
            Map<String, String> headers = new HashMap<>(metadata);
            headers.put("Content-Type", mode == Mode.BAD_MIME ? "text/html" : "image/png");
            headers.put("Content-Length", Integer.toString(object.length + (mode == Mode.BAD_LENGTH ? 1 : 0)));
            byte[] returned = object.clone();
            if (mode == Mode.BAD_HASH) { returned[returned.length - 1] ^= 1; }
            return response(200, returned, headers);
        }
        HttpExecuteResponse delete(String evidence) throws IOException {
            deletes++;
            assertTrue(evidence.contains("OWNERSHIP_RECHECKED_BEFORE_DELETE"));
            assertTrue(evidence.contains("BEFORE_DELETE"));
            if (mode != Mode.DELETE_KEEPS_OBJECT && mode != Mode.DELETE_TIMEOUT_REMAINS) { object = null; }
            if (mode == Mode.DELETE_TIMEOUT_GONE || mode == Mode.DELETE_TIMEOUT_REMAINS) {
                throw new IOException(SENTINEL);
            }
            return response(204, new byte[0], Map.of());
        }
        static HttpExecuteResponse error(int status, String code) {
            byte[] xml = ("<Error><Code>" + code + "</Code><Message>" + SENTINEL + "</Message></Error>")
                    .getBytes(StandardCharsets.UTF_8);
            return response(status, xml, Map.of("Content-Type", "application/xml"));
        }
        static HttpExecuteResponse response(int status, byte[] body, Map<String, String> headers) {
            var builder = SdkHttpResponse.builder().statusCode(status);
            headers.forEach(builder::putHeader);
            return HttpExecuteResponse.builder().response(builder.build())
                    .responseBody(AbortableInputStream.create(new ByteArrayInputStream(body))).build();
        }
        public void close() {}
    }
}
