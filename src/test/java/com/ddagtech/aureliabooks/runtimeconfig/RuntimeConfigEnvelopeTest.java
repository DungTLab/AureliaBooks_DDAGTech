package com.ddagtech.aureliabooks.runtimeconfig;

import org.junit.jupiter.api.Test;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;
import static com.ddagtech.aureliabooks.runtimeconfig.RuntimeConfigFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigEnvelopeTest {
    private static final Clock NOW = Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC);
    private StagingConfigEnvelope envelope() { return new StagingConfigEnvelope(FIRST, REVISION, 990, 1100, values("google"), List.of("google")); }
    private String receive(byte[] bytes, OfflineConfigStore store) { return StagingConfigEnvelope.receive(bytes, REVISION, NOW, "receiver", store); }
    @Test void roundTripStagesWithoutActivationOrDatabaseImageMutation() throws Exception {
        try (var store = new OfflineConfigStore()) {
            assertEquals(FIRST, receive(envelope().encode(), store));
            assertNull(store.confirmed()); assertNull(store.app.generation);
            assertEquals(OfflineConfigStore.IMAGE_A, store.app.image);
            Properties props = new Properties();
            try (var input = Files.newInputStream(store.root.resolve("releases/" + FIRST + "/runtime-integrations.properties"))) { props.load(input); }
            assertTrue(SECRET.equals(props.getProperty("GOOGLE_CLIENT_SECRET")), "ENVELOPE_ROUND_TRIP_FAILED");
            assertEquals("false", props.getProperty("app.r2.enabled"));
            assertEquals("false", props.getProperty("app.r2.upload-enabled"));
        }
    }
    @Test void oneSnapshotSupportsGoogleMailPhoneDemoAndPaymentTogether() throws Exception {
        var input = new HashMap<>(values("google"));
        input.putAll(values("mail")); input.putAll(values("vnpay"));
        input.put("DEMO_SMS_INBOX", "team@example.test");
        var request = new StagingConfigEnvelope(FIRST, REVISION, 990, 1100,
                input, List.of("google", "mail", "sms-demo", "vnpay"));
        try (var store = new OfflineConfigStore()) {
            receive(request.encode(), store);
            Properties props = new Properties();
            try (var stream = Files.newInputStream(store.root.resolve("releases/" + FIRST + "/runtime-integrations.properties"))) { props.load(stream); }
            assertEquals("team@example.test", props.getProperty("demo.sms.inbox"));
            assertTrue(SECRET.equals(props.getProperty("spring.mail.password")), "MAIL_ROUND_TRIP_FAILED");
            assertTrue(SECRET.equals(props.getProperty("GOOGLE_CLIENT_SECRET")), "GOOGLE_ROUND_TRIP_FAILED");
            assertTrue(SECRET.equals(props.getProperty("vnpay.hash-secret")), "PAYMENT_ROUND_TRIP_FAILED");
            assertNull(props.getProperty("spring.profiles.active"));
            assertNull(store.confirmed());
        }
    }
    @Test void replayCannotOverwriteImmutableGeneration() {
        try (var store = new OfflineConfigStore()) {
            byte[] frame = envelope().encode(); receive(frame, store);
            assertThrows(Rejected.class, () -> receive(frame, store)); assertNull(store.confirmed());
        }
    }
    @Test void expiredFutureAndRevisionMismatchLeaveNoGeneration() throws Exception {
        try (var store = new OfflineConfigStore()) {
            for (Clock clock : List.of(Clock.fixed(Instant.ofEpochSecond(989), ZoneOffset.UTC), Clock.fixed(Instant.ofEpochSecond(1100), ZoneOffset.UTC)))
                assertThrows(Rejected.class, () -> StagingConfigEnvelope.receive(envelope().encode(), REVISION, clock, "receiver", store));
            assertThrows(Rejected.class, () -> StagingConfigEnvelope.receive(envelope().encode(), "a".repeat(40), NOW, "receiver", store));
            try (var paths = Files.list(store.root.resolve("releases"))) { assertEquals(0, paths.count()); }
        }
    }
    @Test void lifetimeBoundedAndGroupsCannotDuplicate() {
        assertThrows(Rejected.class, () -> new StagingConfigEnvelope(FIRST, REVISION, 0, 301, values("google"), List.of("google")));
        assertThrows(Rejected.class, () -> new StagingConfigEnvelope(FIRST, REVISION, 100, 100, values("google"), List.of("google")));
        assertThrows(Rejected.class, () -> new StagingConfigEnvelope(FIRST, REVISION, 0, 100, values("google"), List.of("google", "google")));
    }
    @Test void productionAndActivationActionsRejected() {
        try (var store = new OfflineConfigStore()) {
            byte[] frame = envelope().encode();
            // 'staging' starts at offset 8; 'stage' starts at offset 19.
            frame[8] = 'p'; assertThrows(Rejected.class, () -> receive(frame, store));
            byte[] action = envelope().encode(); action[19] = 'x';
            assertThrows(Rejected.class, () -> receive(action, store));
            assertThrows(Rejected.class, () -> StagingConfigEnvelope.receive(envelope().encode(), REVISION, NOW, "installer", store));
        }
    }
    @Test void truncationTrailingBytesOversizeAndNegativeLengthFailWithoutDisclosure() {
        try (var store = new OfflineConfigStore()) {
            byte[] valid = envelope().encode();
            byte[] negative = valid.clone(); ByteBuffer.wrap(negative).putInt(4, -1);
            byte[] invalidUtf8 = valid.clone(); invalidUtf8[8] = (byte)0xff;
            byte[] wrongVersion = valid.clone(); wrongVersion[0] = 0;
            for (byte[] bad : List.of(Arrays.copyOf(valid, 10), Arrays.copyOf(valid, valid.length - 1),
                    Arrays.copyOf(valid, valid.length + 1), new byte[131073], negative, invalidUtf8, wrongVersion)) {
                var error = assertThrows(Rejected.class, () -> receive(bad, store)); absent(SECRET, report(error)); assertNull(error.getCause());
            }
        }
    }
    @Test void duplicateKeysRejectedBeforeStaging() {
        try (var store = new OfflineConfigStore()) {
            byte[] frame = envelope().encode();
            byte[] id = "GOOGLE_CLIENT_ID".getBytes(StandardCharsets.US_ASCII);
            // Replace the second field with the first key, retaining valid framing.
            byte[] secret = "GOOGLE_CLIENT_SECRET".getBytes(StandardCharsets.US_ASCII);
            int index = -1;
            outer: for (int i = 0; i <= frame.length - secret.length; i++) {
                for (int j = 0; j < secret.length; j++) if (frame[i + j] != secret[j]) continue outer;
                index = i; break;
            }
            assertTrue(index >= 4);
            byte[] modified = new byte[frame.length - secret.length + id.length];
            System.arraycopy(frame, 0, modified, 0, index - 4);
            ByteBuffer.wrap(modified).putInt(index - 4, id.length);
            System.arraycopy(id, 0, modified, index, id.length);
            System.arraycopy(frame, index + secret.length, modified, index + id.length, frame.length - index - secret.length);
            assertThrows(Rejected.class, () -> receive(modified, store));
        }
    }
    @Test void objectRenderingIsRedactedAndEncodingDeterministic() {
        var envelope = envelope(); absent(SECRET, envelope.toString());
        assertTrue(Arrays.equals(envelope.encode(), envelope.encode()), "NONDETERMINISTIC_ENVELOPE");
    }
}
