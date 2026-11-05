package com.ddagtech.aureliabooks.runtimeconfig;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ddagtech.aureliabooks.runtimeconfig.ConfigPolicy.*;
import static com.ddagtech.aureliabooks.runtimeconfig.RuntimeConfigFixtures.*;

class RuntimeConfigActivationTest {
    @TempDir Path directory;
    Path root, published;
    long uid;
    int gid;
    RuntimeSnapshotStore store;
    final FakeEngine engine = new FakeEngine();
    class FakeEngine implements RuntimeActivation.Engine {
        int starts, readiness;
        boolean failCreate, failHealth, failAllHealth;
        String image = "ghcr.io/dungtlab/aureliabooks_ddagtech:sha-" + "a".repeat(40);
        @Override public String preflight() { return image; }
        @Override public void recreate() {
            starts++; if (failCreate) { failCreate = false; throw new IllegalStateException("SYNTHETIC_CHILD_OUTPUT"); }
        }
        @Override public boolean ready(String image, String generation, boolean google) {
            readiness++; if (failAllHealth) return false;
            if (failHealth) { failHealth = false; return false; } return true;
        }
    }
    @BeforeEach void setup() throws Exception {
        root = Files.createDirectory(directory.resolve("private")); published = Files.createDirectory(directory.resolve("public"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------")); Files.setPosixFilePermissions(published, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createDirectory(root.resolve("releases"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        uid = ((Number)Files.getAttribute(root, "unix:uid")).longValue(); gid = ((Number)Files.getAttribute(root, "unix:gid")).intValue();
        store = new RuntimeSnapshotStore(root, uid, gid);
        Map<String, String> values = new HashMap<>(); for (String group : List.of("google", "mail", "vnpay")) values.putAll(values(group));
        values.put("VNPAY_RETURN_URL", "https://aurelia-staging.dzungtlab.id.vn/payment/vnpay-return");
        store.stage(FIRST, REVISION, values, List.of("google", "mail", "vnpay"), "receiver");
        store.stage(SECOND, REVISION, values, List.of("google", "mail", "vnpay"), "receiver");
    }
    RuntimeActivation activation() { return new RuntimeActivation("staging", root, published, uid, gid, engine); }
    @Test void activationPublishesSecretFreeOverlayAndPreservesImage() throws Exception {
        RuntimeActivation activation = activation(); assertEquals("NO_ACTIVE_RUNTIME", activation.verify());
        assertEquals("ACTIVE " + FIRST, activation.activateGoogle(FIRST, REVISION)); assertEquals("ACTIVE " + FIRST, activation.verify());
        String overlay = Files.readString(activation.overlayPath()); absent(SECRET, overlay);
        assertTrue(overlay.contains("SPRING_PROFILES_ACTIVE: \"google\"")); assertFalse(overlay.contains("sms-demo"));
        assertTrue(overlay.contains("SERVER_FORWARD_HEADERS_STRATEGY: \"framework\""));
        assertTrue(overlay.contains("read_only: true")); assertFalse(Files.exists(activation.journalPath()));
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(activation.overlayPath()));
        Map<?, ?> document = new Yaml().load(overlay); Map<?, ?> app = (Map<?, ?>)((Map<?, ?>)document.get("services")).get("app");
        assertFalse(app.containsKey("image")); assertFalse(app.containsKey("ports")); assertEquals(1, engine.starts);
    }
    @ParameterizedTest @ValueSource(strings = {"create", "health"})
    void firstActivationFailureRestoresNoOverlay(String failure) {
        RuntimeActivation activation = activation(); engine.failCreate = failure.equals("create"); engine.failHealth = failure.equals("health");
        Exception error = assertThrows(Rejected.class, () -> activation.activateGoogle(FIRST, REVISION));
        absent(SECRET, report(error)); absent("SYNTHETIC_CHILD_OUTPUT", report(error));
        assertEquals("NO_ACTIVE_RUNTIME", activation.verify()); assertFalse(Files.exists(activation.overlayPath())); assertEquals(2, engine.starts);
    }
    @Test void updateFailureRestoresExactPreviousGeneration() throws Exception {
        RuntimeActivation activation = activation(); activation.activateGoogle(FIRST, REVISION);
        byte[] previous = Files.readAllBytes(activation.overlayPath()); engine.failHealth = true;
        assertThrows(Rejected.class, () -> activation.activateGoogle(SECOND, REVISION));
        assertArrayEquals(previous, Files.readAllBytes(activation.overlayPath())); assertEquals("ACTIVE " + FIRST, activation.verify());
        assertEquals("STAGED_NOT_ACTIVE", store.status(SECOND, REVISION));
    }
    @Test void failedRollbackBlocksDeployUntilExplicitRecovery() {
        RuntimeActivation activation = activation(); engine.failAllHealth = true;
        assertThrows(Rejected.class, () -> activation.activateGoogle(FIRST, REVISION)); assertTrue(Files.exists(activation.journalPath()));
        assertThrows(Rejected.class, activation::verify); assertThrows(Rejected.class, () -> activation.activateGoogle(SECOND, REVISION));
        engine.failAllHealth = false; assertEquals("PREVIOUS_RUNTIME_RESTORED", activation().recover()); assertEquals("NO_ACTIVE_RUNTIME", activation().verify());
    }
    @Test void restartedControllerRecoversPreparedJournalAndOverlay() throws Exception {
        RuntimeActivation activation = activation(); activation.activateGoogle(FIRST, REVISION); byte[] previous = Files.readAllBytes(activation.overlayPath());
        Properties journal = new Properties(); journal.setProperty("image", engine.image); journal.setProperty("previous", Base64.getEncoder().encodeToString(previous));
        try (var output = Files.newOutputStream(activation.journalPath(), StandardOpenOption.CREATE_NEW)) { journal.store(output, "synthetic process interruption"); }
        Files.setPosixFilePermissions(activation.journalPath(), PosixFilePermissions.fromString("rw-------"));
        Files.writeString(activation.overlayPath(), activation.render(SECOND, REVISION));
        assertThrows(Rejected.class, activation()::verify); assertEquals("PREVIOUS_RUNTIME_RESTORED", activation().recover());
        assertArrayEquals(previous, Files.readAllBytes(activation.overlayPath())); assertEquals("ACTIVE " + FIRST, activation().verify());
    }
    @Test void imageChangePreventsRecoveryOverwrite() {
        RuntimeActivation activation = activation(); engine.failAllHealth = true; assertThrows(Rejected.class, () -> activation.activateGoogle(FIRST, REVISION));
        engine.failAllHealth = false; engine.image = "ghcr.io/dungtlab/aureliabooks_ddagtech:sha-" + "b".repeat(40);
        assertThrows(Rejected.class, activation::recover); assertTrue(Files.exists(activation.journalPath()));
    }
    @Test void tamperedPayloadCannotActivateOrDeploy() throws Exception {
        Path payload = root.resolve("releases/" + FIRST + "/runtime-integrations.properties");
        Files.setPosixFilePermissions(payload, PosixFilePermissions.fromString("rw-------")); Files.writeString(payload, "app.r2.enabled=true\n");
        Files.setPosixFilePermissions(payload, PosixFilePermissions.fromString("r--r-----"));
        assertThrows(Rejected.class, () -> activation().activateGoogle(FIRST, REVISION)); assertEquals(0, engine.starts);
    }
    @Test void untrustedOverlayPermissionsAndSymlinksFailClosed() throws Exception {
        RuntimeActivation activation = activation(); Path other = directory.resolve("other"); Files.writeString(other, "unchanged");
        Files.createSymbolicLink(activation.overlayPath(), other); assertThrows(Rejected.class, activation::verify); assertEquals("unchanged", Files.readString(other));
        Files.delete(activation.overlayPath()); activation.activateGoogle(FIRST, REVISION); Files.setPosixFilePermissions(activation.overlayPath(), PosixFilePermissions.fromString("rw-rw-rw-"));
        assertThrows(Rejected.class, activation::verify);
    }
    @Test void providerIncompleteSnapshotAndRevisionMismatchRejected() {
        store.stage("3".repeat(32), REVISION, values("google"), List.of("google"), "receiver");
        assertThrows(Rejected.class, () -> activation().activateGoogle("3".repeat(32), REVISION));
        assertThrows(Rejected.class, () -> activation().activateGoogle(FIRST, "a".repeat(40))); assertEquals(0, engine.starts);
    }
    @Test void stagingAndProductionOverlaysBindSeparateRoots() {
        String staging = activation().render(FIRST, REVISION);
        RuntimeActivation production = new RuntimeActivation("production", root, published, uid, gid, engine);
        assertTrue(staging.contains(root.resolve("releases").toString())); assertFalse(production.render(FIRST, REVISION).contains("SPRING_PROFILES_ACTIVE: \"production\""));
        assertThrows(Rejected.class, () -> activation().render("../bad", REVISION));
    }
}
