package com.ddagtech.aureliabooks.config;

import com.ddagtech.aureliabooks.runtimeconfig.StagingConfigEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigCliContractTest {
    private static final String SECRET = "SYNTHETIC_RUNTIME_SECRET_" + UUID.randomUUID();
    private static final String SHA = "a".repeat(40), GENERATION = "b".repeat(32);
    private Map<String, String> fixture() {
        Map<String, String> env = new HashMap<>();
        env.put("GOOGLE_CLIENT_ID", "synthetic-client"); env.put("GOOGLE_CLIENT_SECRET", SECRET);
        env.put("MAIL_USERNAME", "synthetic@example.test"); env.put("MAIL_PASSWORD", SECRET);
        env.put("MAIL_HOST", "smtp.example.test"); env.put("MAIL_PORT", "587");
        env.put("MAIL_AUTH", "true"); env.put("MAIL_STARTTLS", "true"); env.put("MAIL_FROM", "sender@example.test");
        env.put("DEMO_SMS_INBOX", "team@example.test"); env.put("VNPAY_TMN_CODE", "synthetic"); env.put("VNPAY_HASH_SECRET", SECRET);
        env.put("VNPAY_PAY_URL", "https://pay.example.test/pay"); env.put("VNPAY_API_URL", "https://pay.example.test/query");
        env.put("VNPAY_RETURN_URL", "https://shop.example.test/payment/vnpay-return"); env.put("VNPAY_VERSION", "2.1.0");
        env.put("VNPAY_COMMAND", "pay"); env.put("VNPAY_ORDER_TYPE", "other"); env.put("VNPAY_EXPIRE_MINUTES", "15");
        return env;
    }
    private Process child(Map<String, String> environment, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of("target/classes").toAbsolutePath().toString(), "com.ddagtech.aureliabooks.runtimeconfig.RuntimeConfigCli"));
        command.addAll(List.of(args)); ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear(); builder.environment().putAll(environment); return builder.start();
    }
    @Test void senderAndValidatorRoundTripWithoutWritingPayloadArtifacts() throws Exception {
        var prepared = RuntimeConfigurationContractTest.runChild(child(fixture(), "prepare", "staging", SHA, GENERATION), new byte[0], Duration.ofSeconds(5));
        assertEquals(0, prepared.exit()); assertTrue(prepared.error().isEmpty(), "UNSAFE_SENDER_DIAGNOSTIC");
        assertEquals(GENERATION, StagingConfigEnvelope.validate(prepared.body(), SHA, Clock.systemUTC(), "receiver").generation());
        var validated = RuntimeConfigurationContractTest.runChild(child(Map.of(), "validate", "staging", SHA), prepared.body(), Duration.ofSeconds(5));
        assertEquals(0, validated.exit());
        assertFalse(new String(validated.body(), java.nio.charset.StandardCharsets.UTF_8).contains(SECRET), "SECRET_LEAK");
    }
    @Test void missingKeysAndUnsafeFlagsProduceOnlyStaticErrors() throws Exception {
        for (Map<String, String> env : List.of(Map.<String, String>of(), fixture())) {
            var input = new HashMap<>(env); input.put("R2_ENABLED", "true");
            var result = RuntimeConfigurationContractTest.runChild(child(input, "prepare", "staging", SHA, GENERATION), new byte[0], Duration.ofSeconds(5));
            assertEquals(2, result.exit()); assertEquals(0, result.body().length);
            assertTrue("RUNTIME_CONFIG_REJECTED\n".equals(result.error()), "UNSAFE_FAILURE_OUTPUT");
        }
    }
    @Test void productionAndStagingCannotBeMixed() throws Exception {
        var prepared = RuntimeConfigurationContractTest.runChild(child(fixture(), "prepare", "production", SHA, GENERATION), new byte[0], Duration.ofSeconds(5));
        assertEquals(0, prepared.exit());
        var invalid = RuntimeConfigurationContractTest.runChild(child(Map.of(), "validate", "staging", SHA), prepared.body(), Duration.ofSeconds(5));
        assertEquals(2, invalid.exit());
        var valid = RuntimeConfigurationContractTest.runChild(child(Map.of(), "validate", "production", SHA), prepared.body(), Duration.ofSeconds(5));
        assertEquals(0, valid.exit());
    }
    @Test void activationActionDoesNotExistEvenWhenSenderCredentialsArePresent() throws Exception {
        var result = RuntimeConfigurationContractTest.runChild(child(fixture(), "activate", "production", SHA), new byte[0], Duration.ofSeconds(5));
        assertEquals(2, result.exit()); assertEquals(0, result.body().length);
        assertTrue("RUNTIME_CONFIG_REJECTED\n".equals(result.error()), "UNSAFE_ACTIVATION_ERROR");
    }
    @Test @Timeout(18) void hungStdinHasDeadlineAndCannotLeakItsPartialSecret() throws Exception {
        Process process = child(Map.of(), "validate", "staging", SHA);
        try {
            process.getOutputStream().write(SECRET.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            process.getOutputStream().flush(); // Keep stdin open to simulate a stalled SSH stream.
            assertTrue(process.waitFor(14, java.util.concurrent.TimeUnit.SECONDS), "STDIN_DEADLINE_NOT_ENFORCED");
            assertEquals(2, process.exitValue());
            assertEquals(0, process.getInputStream().readAllBytes().length);
            assertFalse(new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains(SECRET), "SECRET_LEAK");
        } finally { process.destroyForcibly(); }
    }
}
