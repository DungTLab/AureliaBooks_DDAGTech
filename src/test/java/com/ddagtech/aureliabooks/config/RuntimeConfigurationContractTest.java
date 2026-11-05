package com.ddagtech.aureliabooks.config;

import com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Boot 3.5 ConfigData/property resolution, NOT an application bootstrap.
 * Every source is synthetic; no System.getenv(), production root, beans or provider calls.
 */
@ExtendWith(OutputCaptureExtension.class)
class RuntimeConfigurationContractTest {
    private static final String SENTINEL = "SYNTHETIC_RUNTIME_SECRET_" + UUID.randomUUID().toString();
    private static final String COMPLEX = " " + SENTINEL + " : = # ! $ \\ \t\r\nĐặng 😀 ";
    @TempDir Path directory;

    private static class IsolatedEnvironment extends MockEnvironment {
        @Override public Map<String, Object> getSystemEnvironment() { return Map.of(); }
        @Override public Map<String, Object> getSystemProperties() { return Map.of(); }
    }

    record Serialized(int exit, byte[] body, String error) {}

    private enum ChildCode { TIMEOUT, OUTPUT_LIMIT, IO_FAILURE, INTERRUPTED, CLEANUP_FAILED }

    private static final class ChildFailure extends RuntimeException {
        private final ChildCode code;
        ChildFailure(ChildCode code) {
            // Neither child diagnostics nor original exception causes enter a report.
            super("SERIALIZER_" + code.name(), null, false, false);
            this.code = code;
        }
    }

    private static CompletableFuture<byte[]> drain(ExecutorService io, InputStream stream, int maximum) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                byte[] bytes = stream.readNBytes(maximum + 1);
                if (bytes.length > maximum) { throw new ChildFailure(ChildCode.OUTPUT_LIMIT); }
                return bytes;
            } catch (IOException e) {
                throw new ChildFailure(ChildCode.IO_FAILURE);
            }
        }, io);
    }

    static Serialized runChild(Process process, byte[] input, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        ExecutorService io = Executors.newFixedThreadPool(3, task -> {
            Thread thread = new Thread(task, "runtime-config-io-" + process.pid());
            thread.setDaemon(true);
            return thread;
        });
        boolean interrupted = false;
        try {
            CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                try (var stream = process.getOutputStream()) { stream.write(input); }
                catch (IOException e) { throw new ChildFailure(ChildCode.IO_FAILURE); }
            }, io);
            CompletableFuture<byte[]> stdout = drain(io, process.getInputStream(), 65536);
            CompletableFuture<byte[]> stderr = drain(io, process.getErrorStream(), 4096);
            CompletableFuture<Void> failure = new CompletableFuture<>();
            for (var task : List.of(writer, stdout, stderr)) {
                task.whenComplete((ignored, error) -> {
                    if (error != null) { failure.completeExceptionally(error); }
                });
            }
            var complete = CompletableFuture.allOf(writer, stdout, stderr, process.onExit());
            CompletableFuture.anyOf(complete, failure).get(
                    Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return new Serialized(process.exitValue(), stdout.join(),
                    new String(stderr.join(), StandardCharsets.US_ASCII));
        } catch (TimeoutException e) {
            throw new ChildFailure(ChildCode.TIMEOUT);
        } catch (InterruptedException e) {
            interrupted = true;
            throw new ChildFailure(ChildCode.INTERRUPTED);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof ChildFailure failure) { throw failure; }
            throw new ChildFailure(ChildCode.IO_FAILURE);
        } finally {
            // Reap the child before waiting for pipe owners; never close a pipe on the
            // calling thread while a backpressured writer holds its stream lock.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) { process.destroyForcibly(); }
            io.shutdownNow();
            long cleanupDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            boolean reaped = false, stopped = false;
            while (!reaped && System.nanoTime() < cleanupDeadline) {
                try {
                    reaped = process.waitFor(Math.max(0, cleanupDeadline - System.nanoTime()),
                            TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) { interrupted = true; }
            }
            while (!stopped && System.nanoTime() < cleanupDeadline) {
                try {
                    stopped = io.awaitTermination(Math.max(0, cleanupDeadline - System.nanoTime()),
                            TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) { Thread.currentThread().interrupt(); }
            if (!reaped || !stopped) { throw new ChildFailure(ChildCode.CLEANUP_FAILED); }
        }
    }

    private Serialized serialize(Map<String, String> values, List<String> groups) throws Exception {
        try { return new Serialized(0, ConfigPolicy.render(values, groups), ""); }
        catch (ConfigPolicy.Rejected e) { return new Serialized(2, new byte[0], e.getMessage()); }
    }

    private Path file(Map<String, String> values, List<String> groups) throws Exception {
        Serialized serialized = serialize(values, groups);
        assertTrue(serialized.exit() == 0, "SERIALIZER_REJECTED_SYNTHETIC_FIXTURE");
        assertTrue(serialized.error().isEmpty(), "SERIALIZER_DIAGNOSTIC");
        Path path = directory.resolve(UUID.randomUUID() + ".properties");
        Files.createFile(path, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        Files.write(path, serialized.body());
        return path;
    }

    private GenericApplicationContext load(Path file, boolean google, Map<String, Object> variables,
                                            Map<String, Object> system, String... args) {
        return loadWithProfiles(file, google ? new String[]{"google"} : new String[0], variables, system, args);
    }

    private GenericApplicationContext loadWithProfiles(Path file, String[] profiles, Map<String, Object> variables,
                                            Map<String, Object> system, String... args) {
        IsolatedEnvironment environment = new IsolatedEnvironment();
        // Restrict base locations to tracked resources, never local real config files.
        environment.setProperty("spring.config.location", "classpath:/application.yaml");
        environment.setProperty("spring.config.additional-location", file.toUri().toString());
        environment.setActiveProfiles(profiles);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment", variables));
        environment.getPropertySources().addFirst(new MapPropertySource("systemProperties", system));
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource("commandLineArgs", args));
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);
        try {
            new ConfigDataApplicationContextInitializer().initialize(context);
            return context;
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }

    private Map<String, String> google() {
        return Map.of("GOOGLE_CLIENT_ID", "synthetic-client", "GOOGLE_CLIENT_SECRET", COMPLEX);
    }

    private Map<String, String> mail() {
        return Map.of("MAIL_HOST", "smtp.example.invalid", "MAIL_PORT", "587",
                "MAIL_USERNAME", "synthetic@example.invalid", "MAIL_PASSWORD", COMPLEX,
                "MAIL_AUTH", "true", "MAIL_STARTTLS", "true", "MAIL_FROM", "synthetic@example.invalid");
    }

    @AfterEach void noSecretValuesInCapturedLogs(CapturedOutput output) {
        assertFalse(output.getAll().contains(SENTINEL), "SECRET_LEAK_IN_LOGS");
    }

    @Test void javaEncodingRoundTripsThroughJavaProperties() throws Exception {
        Serialized result = serialize(google(), List.of("google"));
        assertTrue(result.exit() == 0);
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(result.body()));
        assertTrue(COMPLEX.equals(properties.getProperty("GOOGLE_CLIENT_SECRET")), "JAVA_PROPERTIES_ROUND_TRIP");
    }

    @Test void javaEncodingRoundTripsThroughRealBootLoader() throws Exception {
        try (var context = load(file(mail(), List.of("mail")), false, Map.of(), Map.of())) {
            assertTrue(COMPLEX.equals(context.getEnvironment().getProperty("spring.mail.password")),
                    "BOOT_PROPERTIES_ROUND_TRIP");
            assertEquals(587, Binder.get(context.getEnvironment()).bind("spring.mail.port", Integer.class).get());
            assertTrue(context.getEnvironment().getProperty("spring.mail.properties.mail.smtp.starttls.required",
                    Boolean.class, false));
        }
    }

    @Test void runtimeInboxReachesExistingDemoAdapterWithoutSendingRealSmsOrMail() throws Exception {
        var input = new HashMap<>(mail()); input.put("DEMO_SMS_INBOX", "private-team@example.test");
        try (var context = loadWithProfiles(file(input, List.of("mail", "sms-demo")),
                new String[]{"sms-demo"}, Map.of(), Map.of())) {
            var environment = context.getEnvironment();
            assertEquals("private-team@example.test", environment.getProperty("demo.sms.inbox"));
            var messages = new ArrayList<org.springframework.mail.SimpleMailMessage>();
            var sender = new org.springframework.mail.javamail.JavaMailSenderImpl() {
                @Override public void send(org.springframework.mail.SimpleMailMessage message) {
                    messages.add(new org.springframework.mail.SimpleMailMessage(message));
                }
            };
            var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
            beans.addBean("mail", sender);
            var adapter = new com.ddagtech.aureliabooks.service.impl.DemoSmsOtpDelivery(
                    beans.getBeanProvider(org.springframework.mail.javamail.JavaMailSender.class), environment,
                    environment.getRequiredProperty("app.contact-verification.mail-from"),
                    environment.getRequiredProperty("demo.sms.inbox"));
            adapter.send("SMS", "0912345678", "135790");
            adapter.send("EMAIL", "customer@example.test", "246810");
            assertEquals(2, messages.size());
            assertArrayEquals(new String[]{"private-team@example.test"}, messages.getFirst().getTo());
            assertTrue(messages.getFirst().getSubject().contains("[SMS DEMO]"));
            assertFalse(messages.getFirst().getText().contains("0912345678"));
            assertArrayEquals(new String[]{"customer@example.test"}, messages.get(1).getTo());
            assertFalse(messages.get(1).getSubject().contains("[SMS DEMO]"));
        }
    }

    @Test void googleCredentialsAloneDoNotEnableGoogleProfile() throws Exception {
        try (var context = load(file(google(), List.of("google")), false, Map.of(), Map.of())) {
            assertFalse(Arrays.asList(context.getEnvironment().getActiveProfiles()).contains("google"));
            assertNull(context.getEnvironment().getProperty(
                    "spring.security.oauth2.client.registration.google.client-id"));
        }
    }

    @Test void explicitlyEnabledExistingGoogleProfileResolvesAliases() throws Exception {
        try (var context = load(file(google(), List.of("google")), true, Map.of(), Map.of())) {
            assertTrue(COMPLEX.equals(context.getEnvironment().getProperty(
                    "spring.security.oauth2.client.registration.google.client-secret")), "GOOGLE_ALIAS_ROUND_TRIP");
            assertEquals("{baseUrl}/login/oauth2/code/{registrationId}", context.getEnvironment().getProperty(
                    "spring.security.oauth2.client.registration.google.redirect-uri"));
        }
    }

    @Test void fileOverridesPackagedR2UploadDefaultAndMailDefaults() throws Exception {
        try (var context = load(file(mail(), List.of("mail")), false, Map.of(), Map.of())) {
            var environment = context.getEnvironment();
            assertFalse(environment.getProperty("app.r2.enabled", Boolean.class, true));
            assertFalse(environment.getProperty("app.r2.upload-enabled", Boolean.class, true));
            assertEquals("smtp.example.invalid", environment.getProperty("spring.mail.host"));
        }
    }

    @Test void additionalLocationPreservesPackagedDefaultsOnExactBootVersion() throws Exception {
        assertEquals("3.5.16", org.springframework.boot.SpringBootVersion.getVersion());
        try (var context = load(file(Map.of(), List.of()), false, Map.of(), Map.of())) {
            assertEquals("validate", context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("5MB", context.getEnvironment().getProperty("spring.servlet.multipart.max-file-size"));
        }
    }

    @Test void canonicalFileAvoidsPackagedAliasButNotNativeEnvironmentOverride() throws Exception {
        Path properties = file(mail(), List.of("mail"));
        try (var context = load(properties, false, Map.of("MAIL_PASSWORD", "synthetic-alias"), Map.of())) {
            assertTrue(COMPLEX.equals(context.getEnvironment().getProperty("spring.mail.password")),
                    "CANONICAL_FILE_PRECEDENCE");
        }
        try (var context = load(properties, false, Map.of("SPRING_MAIL_PASSWORD", "synthetic-env"), Map.of())) {
            assertTrue("synthetic-env".equals(context.getEnvironment().getProperty("spring.mail.password")),
                    "NATIVE_ENV_PRECEDENCE");
        }
    }

    @Test void systemPropertySourceOverridesEnvironmentAndFile() throws Exception {
        try (var context = load(file(mail(), List.of("mail")), false,
                Map.of("SPRING_MAIL_PASSWORD", "synthetic-env"),
                Map.of("spring.mail.password", "synthetic-system"))) {
            assertTrue("synthetic-system".equals(context.getEnvironment().getProperty("spring.mail.password")),
                    "SYSTEM_PRECEDENCE");
        }
    }

    @Test void commandLineOverridesSystemEnvironmentAndFile() throws Exception {
        try (var context = load(file(mail(), List.of("mail")), false,
                Map.of("SPRING_MAIL_PASSWORD", "synthetic-env"),
                Map.of("spring.mail.password", "synthetic-system"),
                "--spring.mail.password=synthetic-command")) {
            assertTrue("synthetic-command".equals(context.getEnvironment().getProperty("spring.mail.password")),
                    "COMMAND_LINE_PRECEDENCE");
        }
    }

    @Test void nativeR2OverrideShowsWhyPrecedenceContractMustFailClosed() throws Exception {
        try (var context = load(file(Map.of(), List.of()), false, Map.of("APP_R2_ENABLED", "true"), Map.of())) {
            // Real Boot confirms a file-only false cannot neutralize higher sources.
            assertTrue(Binder.get(context.getEnvironment()).bind("app.r2.enabled", Boolean.class).get());
            assertThrows(IllegalStateException.class, () -> {
                if (context.getEnvironment().getProperty("app.r2.enabled", Boolean.class, false)) {
                    throw new IllegalStateException("R2_OVERRIDE_BLOCKED");
                }
            });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BindingOnlyConfiguration {
        // No component scan, auto-configuration, datasource, HTTP server or providers.
    }

    private org.springframework.context.ConfigurableApplicationContext minimalBoot(
            Path file, String... args) {
        IsolatedEnvironment environment = new IsolatedEnvironment();
        environment.setProperty("spring.config.location", "classpath:/application.yaml");
        environment.setProperty("spring.config.additional-location", file.toUri().toString());
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("SPRING_MAIL_PASSWORD", "synthetic-env",
                        "SPRING_APPLICATION_JSON", "{\"spring\":{\"mail\":{\"password\":\"synthetic-json\"}}}")));
        // JSON's postprocessor uses this standard source name as an insertion point.
        // Keep the normal topology while supplying no real system-property values.
        environment.getPropertySources().addFirst(new MapPropertySource("systemProperties", Map.of()));
        SpringApplication application = new SpringApplication(BindingOnlyConfiguration.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        return application.run(args);
    }

    @Test void realBootJsonPostProcessorOverridesNativeEnvironmentWithoutProviders() throws Exception {
        try (var context = minimalBoot(file(mail(), List.of("mail")))) {
            assertTrue("synthetic-json".equals(context.getEnvironment().getProperty("spring.mail.password")),
                    "BOOT_JSON_PRECEDENCE");
            assertEquals(0, context.getBeanNamesForType(javax.sql.DataSource.class).length);
            assertEquals(0, context.getBeanNamesForType(software.amazon.awssdk.services.s3.S3Client.class).length);
            assertEquals(0, context.getBeanNamesForType(org.springframework.mail.javamail.JavaMailSender.class).length);
        }
    }

    @Test void realBootInsertsCommandLineAboveJsonEnvironmentAndFile() throws Exception {
        try (var context = minimalBoot(file(mail(), List.of("mail")),
                "--spring.mail.password=synthetic-command")) {
            assertTrue("synthetic-command".equals(context.getEnvironment().getProperty("spring.mail.password")),
                    "BOOT_COMMAND_LINE_PRECEDENCE");
        }
    }

    @Test void missingRequiredFileFailsClosed() {
        assertThrows(RuntimeException.class,
                () -> load(directory.resolve("missing.properties"), false, Map.of(), Map.of()));
    }

    @Test void configLoadingDoesNotStartApplicationDatasourceOrProviderBeans() throws Exception {
        try (var context = load(file(mail(), List.of("mail")), false, Map.of(), Map.of())) {
            assertFalse(context.isActive());
            assertEquals(0, context.getBeanDefinitionCount());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"placeholder", "spel", "nul"})
    void unsupportedSecretsAreRejectedWithoutDisclosure(String kind) throws Exception {
        String unsupported = switch (kind) {
            case "placeholder" -> "$" + "{DB_PASS}";
            case "spel" -> "#{T(java.lang.Runtime)}";
            default -> "\0";
        };
        Serialized result = serialize(Map.of("GOOGLE_CLIENT_ID", "synthetic-client",
                "GOOGLE_CLIENT_SECRET", SENTINEL + unsupported), List.of("google"));
        assertEquals(2, result.exit());
        assertEquals(0, result.body().length);
        assertFalse(result.error().contains(SENTINEL), "SECRET_LEAK_IN_DIAGNOSTIC");
    }

    @Test void supplementaryUnicodeAndLiteralDollarArePreserved() throws Exception {
        try (var context = load(file(google(), List.of("google")), false, Map.of(), Map.of())) {
            String loaded = context.getEnvironment().getProperty("GOOGLE_CLIENT_SECRET");
            assertTrue(loaded != null && loaded.contains("😀") && loaded.contains("$"),
                    "UNICODE_OR_DOLLAR_MISMATCH");
            assertTrue(COMPLEX.equals(loaded), "FULL_STRING_MISMATCH");
        }
    }

    private Process syntheticChild(String mode) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of("target/test-classes").toAbsolutePath().toString(),
                "com.ddagtech.aureliabooks.config.RuntimeConfigurationSyntheticChild", mode);
        builder.environment().clear();
        return builder.start();
    }

    private void verifyChildFailure(Process process, byte[] input, Duration budget, ChildCode expected) {
        long start = System.nanoTime();
        try {
            ChildFailure failure = assertThrows(ChildFailure.class,
                    () -> runChild(process, input, budget));
            assertTrue(failure.code == expected, "UNEXPECTED_STATIC_CHILD_ERROR");
            assertFalse(process.isAlive(), "CHILD_NOT_REAPED");
            assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos(),
                    "CHILD_DEADLINE_EXCEEDED");
            StringWriter report = new StringWriter();
            failure.printStackTrace(new PrintWriter(report));
            assertFalse(report.toString().contains(SENTINEL), "SECRET_LEAK_IN_FAILURE_REPORT");
            assertNull(failure.getCause(), "UNSAFE_EXCEPTION_CAUSE");
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); }
        }
    }

    @Test @Timeout(6)
    void hungChildHasBoundedDeadlineAndIsReaped() throws Exception {
        verifyChildFailure(syntheticChild("hang"),
                new byte[0], Duration.ofMillis(250), ChildCode.TIMEOUT);
    }

    @Test @Timeout(6)
    void blockedStdinWriterIsIncludedInDeadline() throws Exception {
        byte[] input = new byte[1024 * 1024];
        Arrays.fill(input, (byte) 'x');
        byte[] secret = SENTINEL.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(secret, 0, input, 0, secret.length);
        verifyChildFailure(syntheticChild("hang"),
                input, Duration.ofMillis(250), ChildCode.TIMEOUT);
    }

    @ParameterizedTest @ValueSource(strings = {"stdout", "stderr"}) @Timeout(6)
    void floodedPipeIsBoundedWithoutDeadlockOrSecretDiagnostics(String pipe) throws Exception {
        // The source code contains no credential value: synthetic bytes use stdin only.
        verifyChildFailure(syntheticChild(pipe), SENTINEL.getBytes(StandardCharsets.US_ASCII),
                Duration.ofSeconds(2), ChildCode.OUTPUT_LIMIT);
    }

    @Test @Timeout(6)
    void timeoutFailureDoesNotIncludeChildSecretStderr() throws Exception {
        verifyChildFailure(syntheticChild("stderr-hang"), SENTINEL.getBytes(StandardCharsets.US_ASCII),
                Duration.ofMillis(250), ChildCode.TIMEOUT);
    }

    @Test @Timeout(6)
    void nonzeroExitAssertionDoesNotPrintSecretDiagnostic() throws Exception {
        Process process = syntheticChild("nonzero");
        Serialized result = runChild(process, SENTINEL.getBytes(StandardCharsets.US_ASCII),
                Duration.ofSeconds(2));
        assertFalse(process.isAlive(), "CHILD_NOT_REAPED");
        assertTrue(result.error().contains(SENTINEL), "SYNTHETIC_FAILURE_FIXTURE_MISSING");
        AssertionError failure = assertThrows(AssertionError.class,
                () -> assertTrue(result.exit() == 0, "SERIALIZER_CHILD_FAILED"));
        StringWriter report = new StringWriter();
        failure.printStackTrace(new PrintWriter(report));
        assertFalse(report.toString().contains(SENTINEL), "SECRET_LEAK_IN_FAILURE_REPORT");
    }

    @Test @Timeout(6)
    void interruptedCallerStillReapsChildAndPreservesInterruptStatus() throws Exception {
        Process process = syntheticChild("hang");
        try {
            Thread.currentThread().interrupt();
            verifyChildFailure(process, new byte[0], Duration.ofSeconds(1), ChildCode.INTERRUPTED);
            assertTrue(Thread.currentThread().isInterrupted(), "CALLER_INTERRUPT_NOT_PRESERVED");
        } finally {
            Thread.interrupted(); // Do not leak the synthetic interrupt into another test.
            if (process.isAlive()) { process.destroyForcibly(); }
        }
    }
}
