package com.ddagtech.aureliabooks.service.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-source-only protocol. No main(), environment lookup, Spring context or default
 * network transport. Offline tests inject an in-memory SDK HTTP transport.
 */
final class R2SmokeProtocol {
    static final String BUCKET = "aureliabooks-staging-assets";
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final int MAX_FIXTURE_BYTES = 64 * 1024;

    record Plan(String sourceRevision, UUID imageId, String accountId, String endpoint,
                String bucket, String jurisdiction, String pngSha256, int pngBytes,
                String approvedDeleteKey, Instant approvalExpiresAt,
                boolean executionApproved, boolean bucketIdentityAndPrivacyVerified,
                boolean lifecycleAndExclusiveWindowVerified, boolean scopedObjectReadWriteTokenVerified,
                boolean tokenStatusAndExpiryVerified, boolean secretInjectionAndRotationVerified) {
        @JsonProperty(value = "objectKey", access = JsonProperty.Access.READ_ONLY)
        String key() { return "products/" + imageId + ".png"; }

        void validate(String currentRevision, Clock clock) {
            require(sourceRevision != null && sourceRevision.matches("[0-9a-f]{40}")
                    && sourceRevision.equals(currentRevision), "Source revision is not approved");
            require(imageId != null && imageId.version() == 4 && imageId.variant() == 2,
                    "An approved UUID v4 is required");
            require(accountId != null && accountId.matches("[0-9a-f]{32}"),
                    "Account identity is not approved");
            // Deliberately only the default jurisdiction: review this policy before
            // supporting another jurisdiction. Exact equality rejects ports/paths/etc.
            require(("https://" + accountId + ".r2.cloudflarestorage.com").equals(endpoint)
                    && "DEFAULT".equals(jurisdiction), "Exact endpoint/jurisdiction is not approved");
            require(BUCKET.equals(bucket), "Only the named staging bucket is allowed");
            require(pngSha256 != null && pngSha256.matches("[0-9a-f]{64}")
                    && pngBytes > 0 && pngBytes <= MAX_FIXTURE_BYTES, "Invalid PNG evidence");
            require(key().equals(approvedDeleteKey), "DELETE must be approved for this exact key");
            require(approvalExpiresAt != null && approvalExpiresAt.isAfter(clock.instant()),
                    "Approval is missing or expired");
            require(executionApproved && bucketIdentityAndPrivacyVerified
                    && lifecycleAndExclusiveWindowVerified && scopedObjectReadWriteTokenVerified
                    && tokenStatusAndExpiryVerified && secretInjectionAndRotationVerified,
                    "External approval/metadata gates remain unconfirmed");
        }
    }

    enum Outcome {
        PASS, PREEXISTING_OBJECT, PREFLIGHT_UNVERIFIED, CLIENT_POLICY_BLOCKED,
        PUT_NOT_SENT, PUT_REJECTED, OWNERSHIP_UNVERIFIED, CLEANUP_UNVERIFIED,
        PUT_UNCERTAIN_CLEANED, DELETE_UNCERTAIN_ABSENT
    }
    record Result(Outcome outcome, String key, boolean absenceVerified) {}
    enum Phase { PREFLIGHT, STORE, VERIFY, RECHECK, DELETE, ABSENCE }

    /** A draft is deliberately not executable. Generates one ID and never reads credentials. */
    static Plan prepare(Path directory, String revision) throws IOException {
        require(revision != null && revision.matches("[0-9a-f]{40}"), "A source revision is required");
        Files.createDirectory(directory); // No reuse/overwrite of a previous run.
        byte[] input = fixture();
        byte[] png = new ProductImageProcessor().toPng(multipart(input));
        Plan draft = new Plan(revision, UUID.randomUUID(), "", "", BUCKET, "NEEDS_CONFIRMATION",
                sha256(png), png.length, "", null, false, false, false, false, false, false);
        Files.write(directory.resolve("source.jpg"), input, StandardOpenOption.CREATE_NEW);
        Files.write(directory.resolve("normalized.png"), png, StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve("approval-draft.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(draft) + "\n",
                StandardOpenOption.CREATE_NEW);
        return draft;
    }

    /** Same SDK/settings as production, except retries explicitly disabled for this smoke. */
    @SuppressWarnings("deprecation")
    static S3ClientBuilder sdkBuilder(Plan plan, String revision, Clock clock,
                                     AwsCredentialsProvider credentials) {
        plan.validate(revision, clock);
        require(credentials instanceof StaticCredentialsProvider, "Static credentials are required");
        return S3Client.builder().endpointOverride(URI.create(plan.endpoint()))
                .region(Region.of("auto")).credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)).retryPolicy(RetryPolicy.none()));
    }

    /**
     * Factory is not called until all attestations, fixture digest and a durable new
     * journal exist. There is intentionally no built-in credential loader/live CLI.
     */
    static Result run(Plan plan, String revision, Clock clock, byte[] input,
                      Path journalPath, Function<Plan, S3Client> factory) throws IOException {
        plan.validate(revision, clock);
        byte[] normalized = new ProductImageProcessor().toPng(multipart(input));
        require(normalized.length == plan.pngBytes() && sha256(normalized).equals(plan.pngSha256()),
                "Fixture does not match approved normalized PNG");
        try (Journal journal = new Journal(journalPath)) {
            journal.append(Map.of("event", "PLAN_BEFORE_CLIENT_CREATION", "plan", plan));
            try (S3Client delegate = factory.apply(plan)) {
                if (!validClient(delegate, plan)) {
                    return finish(journal, plan, Outcome.CLIENT_POLICY_BLOCKED, false);
                }
                Guard guard = new Guard(plan, journal, delegate, clock);
                S3Client client = guard.client();
                try {
                    if (!absent(client, plan)) {
                        return finish(journal, plan, Outcome.PREEXISTING_OBJECT, false);
                    }
                } catch (SdkException e) {
                    journal.event("PREFLIGHT_ERROR_" + e.getClass().getSimpleName());
                    return finish(journal, plan, Outcome.PREFLIGHT_UNVERIFIED, false);
                }
                guard.phase = Phase.STORE;
                AtomicBoolean idIssued = new AtomicBoolean();
                R2ProductImageStorage storage = new R2ProductImageStorage(client,
                        new ProductImageProcessor(), plan.bucket(), () -> {
                            require(!idIssued.getAndSet(true), "Smoke ID has already been consumed");
                            return plan.imageId();
                        });
                boolean putAcknowledged = false;
                try {
                    require(plan.key().equals(storage.store(multipart(input))), "Unexpected returned key");
                    putAcknowledged = true;
                    journal.event("PUT_ACKNOWLEDGED");
                } catch (RuntimeException e) {
                    if (e instanceof UncheckedIOException io) { throw io; }
                    journal.event("PUT_ERROR_" + e.getClass().getSimpleName());
                    if (!guard.putAttempted) {
                        return finish(journal, plan, Outcome.PUT_NOT_SENT, false);
                    }
                    if (e.getCause() instanceof S3Exception service
                            && service.statusCode() >= 400 && service.statusCode() < 500) {
                        // A rejected conditional write cannot establish ownership, even
                        // if someone copied the approval's public marker/hash onto a key.
                        return finish(journal, plan, Outcome.PUT_REJECTED, false);
                    }
                }
                guard.phase = Phase.VERIFY;
                if (!ownedPng(storage, guard, plan)) {
                    // In particular, 404 after a timed-out PUT is NOT proof of absence:
                    // the original request may still commit. No retry, new ID or DELETE.
                    return finish(journal, plan, Outcome.OWNERSHIP_UNVERIFIED, false);
                }
                journal.event("OWNED_PNG_SHA256_VERIFIED");
                guard.phase = Phase.RECHECK;
                if (!ownedPng(storage, guard, plan)) {
                    return finish(journal, plan, Outcome.OWNERSHIP_UNVERIFIED, false);
                }
                journal.event("OWNERSHIP_RECHECKED_BEFORE_DELETE");
                guard.phase = Phase.DELETE;
                boolean deleteAcknowledged = false;
                try {
                    client.deleteObject(DeleteObjectRequest.builder()
                            .bucket(plan.bucket()).key(plan.key()).build());
                    deleteAcknowledged = true;
                    journal.event("DELETE_ACKNOWLEDGED");
                } catch (SdkException e) {
                    journal.event("DELETE_ERROR_" + e.getClass().getSimpleName());
                }
                guard.phase = Phase.ABSENCE;
                try {
                    if (!absent(client, plan)) {
                        return finish(journal, plan, Outcome.CLEANUP_UNVERIFIED, false);
                    }
                } catch (SdkException e) {
                    journal.event("ABSENCE_CHECK_ERROR_" + e.getClass().getSimpleName());
                    return finish(journal, plan, Outcome.CLEANUP_UNVERIFIED, false);
                }
                return finish(journal, plan, !putAcknowledged ? Outcome.PUT_UNCERTAIN_CLEANED
                        : !deleteAcknowledged ? Outcome.DELETE_UNCERTAIN_ABSENT : Outcome.PASS, true);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean validClient(S3Client client, Plan plan) {
        var configuration = client.serviceClientConfiguration();
        if (configuration == null) { return false; }
        var override = configuration.overrideConfiguration();
        return configuration.endpointOverride().filter(URI.create(plan.endpoint())::equals).isPresent()
                && Region.of("auto").equals(configuration.region())
                && configuration.credentialsProvider() instanceof StaticCredentialsProvider
                && override != null
                && override.retryPolicy().filter(p -> p.numRetries() == 0).isPresent()
                && override.apiCallTimeout().filter(Duration.ofSeconds(30)::equals).isPresent()
                && override.apiCallAttemptTimeout().filter(Duration.ofSeconds(10)::equals).isPresent();
    }

    private static boolean ownedPng(R2ProductImageStorage storage, Guard guard, Plan plan) {
        try {
            byte[] png = storage.read(plan.imageId().toString());
            return png.length == plan.pngBytes() && sha256(png).equals(plan.pngSha256())
                    && plan.imageId().toString().equals(guard.lastMetadata.get("smoke-run"))
                    && plan.pngSha256().equals(guard.lastMetadata.get("smoke-sha256"));
        } catch (RuntimeException e) {
            if (e instanceof UncheckedIOException io) { throw io; }
            // Do not log exception messages: SDK messages can contain request/credential details.
            guard.journal.event("OWNERSHIP_READ_ERROR_" + e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean absent(S3Client client, Plan plan) {
        try (var stream = client.getObject(GetObjectRequest.builder()
                .bucket(plan.bucket()).key(plan.key()).build())) {
            stream.abort(); // Existing/foreign data must not be downloaded.
            return false;
        } catch (S3Exception e) {
            // Generic 404, NoSuchBucket and permission failures are not enough.
            if (e.statusCode() == 404 && e.awsErrorDetails() != null
                    && "NoSuchKey".equals(e.awsErrorDetails().errorCode())) {
                return true;
            }
            throw e;
        } catch (IOException e) {
            throw SdkClientException.create("Cannot verify exact-key absence", e);
        }
    }

    private static Result finish(Journal journal, Plan plan, Outcome outcome, boolean absent) {
        journal.append(Map.of("event", "FINISH", "outcome", outcome.name(),
                "absenceVerified", absent));
        return new Result(outcome, plan.key(), absent);
    }

    /** Exact typed operation + phase + count allowlist. Nothing forwards by default. */
    static final class Guard {
        final Plan plan;
        final Journal journal;
        final S3Client delegate;
        final Clock clock;
        final EnumSet<Phase> consumed = EnumSet.noneOf(Phase.class);
        Phase phase = Phase.PREFLIGHT;
        boolean putAttempted;
        Map<String, String> lastMetadata = Map.of();

        Guard(Plan plan, Journal journal, S3Client delegate, Clock clock) {
            this.plan = plan; this.journal = journal; this.delegate = delegate;
            this.clock = clock;
        }

        S3Client client() {
            return (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(),
                    new Class<?>[]{S3Client.class}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "ExactKeySmokeClient";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new SecurityException("Unsupported object method");
                    };
                }
                boolean get = args != null && args.length == 1
                        && args[0] instanceof GetObjectRequest && method.getName().equals("getObject")
                        && EnumSet.of(Phase.PREFLIGHT, Phase.VERIFY, Phase.RECHECK, Phase.ABSENCE).contains(phase);
                boolean put = phase == Phase.STORE && method.getName().equals("putObject")
                        && args != null && args.length == 2 && args[0] instanceof PutObjectRequest
                        && args[1] instanceof RequestBody;
                boolean delete = phase == Phase.DELETE && method.getName().equals("deleteObject")
                        && args != null && args.length == 1 && args[0] instanceof DeleteObjectRequest;
                if (!(get || put || delete) || consumed.contains(phase)) {
                    throw new SecurityException("Smoke operation or count is outside approval");
                }
                plan.validate(plan.sourceRevision(), clock);
                S3Request request = (S3Request) args[0];
                require(plan.bucket().equals(request.getValueForField("Bucket", String.class).orElse(null))
                        && plan.key().equals(request.getValueForField("Key", String.class).orElse(null)),
                        "Smoke bucket/key is outside approval");
                if (put) {
                    PutObjectRequest original = (PutObjectRequest) request;
                    require("image/png".equals(original.contentType()), "Unexpected PUT content type");
                    byte[] actual;
                    try (InputStream body = ((RequestBody) args[1]).contentStreamProvider().newStream()) {
                        actual = body.readNBytes(plan.pngBytes() + 1);
                    }
                    require(actual.length == plan.pngBytes() && sha256(actual).equals(plan.pngSha256()),
                            "Actual PUT PNG differs from approved bytes");
                    // Smoke-only safeguards; production uploads do not get this metadata/header.
                    args[0] = original.toBuilder().ifNoneMatch("*").metadata(Map.of(
                            "smoke-run", plan.imageId().toString(), "smoke-sha256", plan.pngSha256())).build();
                }
                journal.event("BEFORE_" + phase.name()); // force to disk BEFORE delegate I/O
                consumed.add(phase);
                if (put) { putAttempted = true; }
                try {
                    Object result = method.invoke(delegate, args);
                    if (get) {
                        @SuppressWarnings("unchecked")
                        var stream = (software.amazon.awssdk.core.ResponseInputStream<GetObjectResponse>) result;
                        lastMetadata = Map.copyOf(stream.response().metadata());
                    }
                    return result;
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }
    }

    /** Exclusive create + fsync for the plan and each event; never logs raw SDK errors. */
    static final class Journal implements AutoCloseable {
        final FileChannel channel;
        Journal(Path path) throws IOException {
            channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel directory = FileChannel.open(path.toAbsolutePath().getParent(),
                    StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException e) {
                channel.close();
                throw e;
            }
        }
        void event(String event) { append(Map.of("event", event)); }
        void append(Object value) {
            try {
                byte[] bytes = (JSON.writeValueAsString(Map.of("at", Instant.now().toString(),
                        "record", value)) + "\n").getBytes(StandardCharsets.UTF_8);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) { channel.write(buffer); }
                channel.force(true);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot persist smoke evidence; stop I/O", e);
            }
        }
        public void close() throws IOException { channel.close(); }
    }

    static MockMultipartFile multipart(byte[] input) {
        return new MockMultipartFile("image", "synthetic.jpg", "image/jpeg", input.clone());
    }
    static byte[] fixture() throws IOException {
        BufferedImage image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, (x * 13 << 16) | (y * 17 << 8) | 73);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "jpeg", out)) { throw new IOException("JPEG encoder unavailable"); }
            return out.toByteArray();
        }
    }
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
