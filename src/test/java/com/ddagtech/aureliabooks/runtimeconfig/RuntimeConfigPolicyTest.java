package com.ddagtech.aureliabooks.runtimeconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;
import static com.ddagtech.aureliabooks.runtimeconfig.RuntimeConfigFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigPolicyTest {
    @Test void explicitMappingAndUniqueTargets() {
        assertEquals(25, FIELDS.size());
        assertEquals(FIELDS.size(), FIELDS.values().stream().map(Field::target).distinct().count());
        for (String key : List.of("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "MAIL_PASSWORD", "VNPAY_TMN_CODE", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY"))
            assertEquals("ES", FIELDS.get(key).scope());
    }
    @Test void bothR2FlagsDefaultToFalseAndRequireOptIn() {
        assertTrue(Arrays.equals("app.r2.enabled=false\napp.r2.upload-enabled=false\n".getBytes(StandardCharsets.US_ASCII), render(Map.of(), List.of())));
        FLAGS.keySet().forEach(key -> assertThrows(Rejected.class, () -> render(Map.of(key, "true"), List.of())));
    }
    @Test void r2OptInRequiresCompleteCredentialsAndEnvironmentBucket() {
        Map<String, String> input = values("r2");
        var frame = new StagingConfigEnvelope("staging", FIRST, REVISION, 1, 2, input, List.of("r2"));
        absent(SECRET, frame.toString());
        assertThrows(Rejected.class, () -> new StagingConfigEnvelope(
                "production", FIRST, REVISION, 1, 2, input, List.of("r2")));
        for (String key : R2_REQUIRED) {
            var incomplete = new HashMap<>(input);
            incomplete.remove(key);
            assertThrows(Rejected.class, () -> render(incomplete, List.of("r2")));
        }
        var readOnly = replacing("r2", "R2_UPLOAD_ENABLED", "false");
        assertTrue(render(readOnly, List.of("r2")).length > 0);
        assertThrows(Rejected.class, () -> render(replacing("r2", "R2_ENABLED", "false"), List.of("r2")));
    }
    @ParameterizedTest @ValueSource(strings = {"R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "DB_PASS", "MYSQL_ROOT_PASSWORD", "APP_IMAGE",
            "BOOTSTRAP_ADMIN_PASSWORD", "SPRING_PROFILES_ACTIVE", "spring.config.import", "spring.datasource.url", "JAVA_TOOL_OPTIONS", "OTHER"})
    void forbiddenKeysCannotDiscloseValue(String key) {
        var error = assertThrows(Rejected.class, () -> render(Map.of(key, SECRET), List.of()));
        absent(SECRET, report(error)); assertNull(error.getCause());
    }
    @ParameterizedTest @ValueSource(strings = {"google", "mail", "vnpay"})
    void completeGroups(String group) { assertTrue(render(values(group), List.of(group)).length > 0); }
    @Test void incompleteUndeclaredUnknownAndNullGroupsRejected() {
        assertThrows(Rejected.class, () -> render(Map.of("GOOGLE_CLIENT_ID", "synthetic"), List.of("google")));
        assertThrows(Rejected.class, () -> render(values("google"), List.of()));
        assertThrows(Rejected.class, () -> render(Map.of(), List.of("anything")));
        assertThrows(Rejected.class, () -> render(Map.of(), Arrays.asList((String)null)));
        var invalid = new HashMap<String, String>(); invalid.put(null, SECRET);
        assertThrows(Rejected.class, () -> render(invalid, List.of()));
    }
    @Test void smsDemoRequiresCompleteSmtpAndExplicitGroup() {
        assertThrows(Rejected.class, () -> render(Map.of("DEMO_SMS_INBOX", "team@example.test"), List.of("sms-demo")));
        var input = new HashMap<>(values("mail")); input.put("DEMO_SMS_INBOX", "team@example.test");
        assertThrows(Rejected.class, () -> render(input, List.of("mail")));
        String body = new String(render(input, List.of("mail", "sms-demo")), StandardCharsets.US_ASCII);
        assertTrue(body.contains("demo.sms.inbox=team@example.test\n"));
        assertFalse(body.contains("spring.profiles"));
        assertEquals("EV", FIELDS.get("DEMO_SMS_INBOX").scope());
    }
    @ParameterizedTest @ValueSource(strings = {"a@example.test,b@example.test", "Name <a@example.test>", "a@example.test\nBcc:x@example.test", "not-an-email", " a@example.test"})
    void demoInboxIsOneAddressWithoutHeaderInjection(String inbox) {
        var input = new HashMap<>(values("mail")); input.put("DEMO_SMS_INBOX", inbox);
        assertThrows(Rejected.class, () -> render(input, List.of("mail", "sms-demo")));
    }
    @Test void exactPropertiesEscapingAndRoundTrip() throws Exception {
        String complex = " : = # ! $ \\ \t\n\r\fĐặng 😀 ";
        String expected = "\\ \\:\\ \\=\\ \\#\\ \\!\\ $\\ \\\\\\ \\t\\n\\r\\f\\u0110\\u1eb7ng\\ \\ud83d\\ude00\\ ";
        assertTrue(expected.equals(escape(complex)), "EXACT_ESCAPE_MISMATCH");
        String secret = SECRET + complex;
        byte[] body = render(replacing("google", "GOOGLE_CLIENT_SECRET", secret), List.of("google"));
        Properties props = new Properties(); props.load(new ByteArrayInputStream(body));
        assertTrue(secret.equals(props.getProperty("GOOGLE_CLIENT_SECRET")), "ROUND_TRIP_FAILED");
        assertTrue(new String(body, StandardCharsets.US_ASCII).chars().allMatch(c -> c < 128));
    }
    @Test void unsupportedValuesRejectedWithoutDisclosure() {
        for (String suffix : List.of("${DB_PASS}", "#{unsafe}", "\0", "\ud800", "\udc00", "x".repeat(4097))) {
            var e = assertThrows(Rejected.class, () -> render(replacing("google", "GOOGLE_CLIENT_SECRET", SECRET + suffix), List.of("google")));
            absent(SECRET, report(e));
        }
    }
    @Test void serializerDoesNotEnableProfilesOrGoogleRegistration() {
        String body = new String(render(values("google"), List.of("google")), StandardCharsets.US_ASCII);
        absent("spring.profiles", body); absent("spring.security.oauth2", body);
    }
    @Test void invalidUrlsNumbersAndSenderFormatsRejected() {
        for (String url : List.of("http://x.invalid", "https://u:p@x.invalid", "https://x.invalid/?q=1", "https://x.invalid/#x",
                "https://x.invalid:99999", "https://x.invalid/bad path", "https://x.invalid:bad"))
            assertThrows(Rejected.class, () -> render(replacing("vnpay", "VNPAY_RETURN_URL", url), List.of("vnpay")));
        for (String port : List.of("0", "65536", "-1", "01", "x"))
            assertThrows(Rejected.class, () -> render(replacing("mail", "MAIL_PORT", port), List.of("mail")));
        assertThrows(Rejected.class, () -> render(replacing("mail", "MAIL_HOST", "bad host"), List.of("mail")));
        assertThrows(Rejected.class, () -> render(replacing("mail", "MAIL_FROM", "invalid"), List.of("mail")));
    }
    @Test void authenticatedStartTlsRequired() {
        for (String key : List.of("MAIL_AUTH", "MAIL_STARTTLS")) {
            assertThrows(Rejected.class, () -> render(replacing("mail", key, "false"), List.of("mail")));
            assertThrows(Rejected.class, () -> render(replacing("mail", key, "TRUE"), List.of("mail")));
        }
    }
    @Test void r2ScopeCannotExpand() {
        render(Map.of("R2_ENDPOINT", "https://" + "0".repeat(32) + ".r2.cloudflarestorage.com", "R2_BUCKET", "aureliabooks-staging-assets"), List.of());
        assertThrows(Rejected.class, () -> render(Map.of("R2_ENDPOINT", "https://example.invalid"), List.of()));
        assertThrows(Rejected.class, () -> render(Map.of("R2_BUCKET", "production"), List.of()));
    }
    @Test void precedenceRequiresReviewedNamesAndNoOverrides() {
        precedence(List.of("DB_PASS", "APP_IMAGE", "PORT"), List.of(), List.of());
        for (String name : List.of("MAIL_PASSWORD", "SPRING_MAIL_PASSWORD", "APP_R2_ENABLED", "R2_ENABLED", "SPRING_APPLICATION_JSON",
                "SPRING_CONFIG_LOCATION", "SPRING_PROFILES_ACTIVE", "JAVA_TOOL_OPTIONS", "UNKNOWN"))
            assertThrows(Rejected.class, () -> precedence(List.of(name), List.of(), List.of()));
        assertThrows(Rejected.class, () -> precedence(List.of(), List.of("override"), List.of()));
        assertThrows(Rejected.class, () -> precedence(List.of(), List.of(), List.of("override")));
    }
    @Test void generationAndRevisionRejectTraversal() {
        for (String id : List.of("../escape", "F".repeat(32), ""))
            assertThrows(Rejected.class, () -> generation(id, REVISION));
        assertThrows(Rejected.class, () -> generation(FIRST, "bad"));
    }
    @Test void escapedPayloadSizeIsBoundedBeforeAnyWrite() {
        Map<String, String> oversized = new HashMap<>();
        FIELDS.forEach((key, field) -> {
            if (field.group().equals("google") || key.equals("MAIL_USERNAME") || key.equals("MAIL_PASSWORD")
                    || key.equals("VNPAY_TMN_CODE") || key.equals("VNPAY_HASH_SECRET")) oversized.put(key, "Đ".repeat(4096));
        });
        for (String group : List.of("mail", "vnpay")) values(group).forEach(oversized::putIfAbsent);
        var error = assertThrows(Rejected.class, () -> render(oversized, List.of("google", "mail", "vnpay")));
        assertTrue("PAYLOAD_TOO_LARGE".equals(error.getMessage()), "UNEXPECTED_SIZE_ERROR");
    }
    @Test void removedSmsProviderKeysAndGroupAreRejected() {
        for (String key : List.of("TWILIO_ACCOUNT_SID", "TWILIO_AUTH_TOKEN", "TWILIO_FROM")) {
            var error = assertThrows(Rejected.class, () -> render(Map.of(key, SECRET), List.of()));
            absent(SECRET, report(error));
        }
        assertThrows(Rejected.class, () -> render(Map.of(), List.of("twilio")));
    }
    @ParameterizedTest @ValueSource(strings = {"text", "binary", "missing-marker"})
    void deliberatelyFailedAssertionsCannotDiscloseSecrets(String kind) {
        AssertionError error = assertThrows(AssertionError.class, () -> {
            if (kind.equals("missing-marker")) assertTrue(("password=" + SECRET).contains("required-marker"), "EXPECTED_MARKER_MISSING");
            else if (kind.equals("binary")) assertFalse(Arrays.equals(SECRET.getBytes(StandardCharsets.US_ASCII), SECRET.getBytes(StandardCharsets.US_ASCII)), "UNEXPECTED_OR_SECRET_VALUE");
            else absent(SECRET, "failure:" + SECRET);
        });
        absent(SECRET, report(error));
    }
}
