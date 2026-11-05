package com.ddagtech.aureliabooks.tooling;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogSeedTest {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(strings = {
            "ALTER TABLE categories ADD code INT;", "DROP TABLE categories;", "DELETE FROM categories;",
            "INSERT INTO authors (name) VALUES ('Test');", "INSERT INTO categories VALUES ('Books'); DELETE FROM authors;",
            "INSERT INTO categories VALUES ('Books') ON DUPLICATE KEY UPDATE name='Other';",
            "INSERT INTO categories (name) SELECT email FROM users;", "INSERT INTO categories VALUES (LOAD_FILE('/etc/passwd'));",
            "/*!50000 DROP TABLE categories */;", "/*+ hint */ INSERT INTO categories VALUES ('A');",
            "INSERT INTO categories VALUES ('Books')", "INSERT INTO categories VALUES ('Books);", "/* unclosed",
            "INSERT INTO categories VALUES (SLEEP(5));", "INSERT INTO categories VALUES (BENCHMARK(10, 1));"})
    void unsafeSqlRejected(String sql) { assertThrows(IllegalArgumentException.class, () -> CatalogSeed.checkSql("001_categories.sql", sql)); }
    @ParameterizedTest @ValueSource(strings = {
            "INSERT INTO categories (name) VALUES ('Books');", "INSERT INTO categories VALUES ('Art; Literature');",
            "INSERT INTO categories VALUES ('DROP TABLE');", "-- DELETE FROM categories\nINSERT INTO categories VALUES ('Books');",
            "/* ALTER TABLE categories */ INSERT INTO categories VALUES ('Books');", "INSERT INTO categories VALUES ('It''s good');",
            "# UPDATE\nINSERT INTO categories VALUES ('Books');", "INSERT INTO categories VALUES ('a\\\";b');"})
    void safeSqlAccepted(String sql) { assertEquals("INSERT_ONLY (1 statements)", CatalogSeed.checkSql("001_categories.sql", sql)); }
    @Test void multipleInsertsAndTemplates() {
        assertEquals("TEMPLATE_ONLY", CatalogSeed.checkSql("001_categories.sql", "-- TODO\n/* records */"));
        assertEquals("INSERT_ONLY (2 statements)", CatalogSeed.checkSql("001_categories.sql", "INSERT INTO categories VALUES ('a');INSERT INTO categories VALUES ('b');"));
    }
    Map<String, Object> entry() {
        Map<String, Object> row = new HashMap<>(Map.of("seed_file", CatalogSeed.FILES.getFirst(), "sha256", "a".repeat(64), "git_commit", "f".repeat(40), "environment", "staging", "status", "APPLIED"));
        row.put("started_at", null); row.put("completed_at", null); return row;
    }
    Map<String, Object> ledger(List<?> rows) { return new HashMap<>(Map.of("version", 1, "environment", "staging", "entries", rows)); }
    Path writeLedger(Object data) throws Exception { Path path = root.resolve("ledger.json"); Files.writeString(path, ToolJson.write(data)); return path; }
    @Test void emptyAndAppliedLedgers() throws Exception {
        assertTrue(CatalogSeed.loadLedger(writeLedger(ledger(List.of())), "staging").isEmpty());
        assertEquals("APPLIED", CatalogSeed.loadLedger(writeLedger(ledger(List.of(entry()))), "staging").get(CatalogSeed.FILES.getFirst()).get("status"));
    }
    @ParameterizedTest @ValueSource(strings = {"version", "environment", "entries", "duplicate", "sha256", "seed_file", "status", "incomplete", "entry_environment", "root"})
    void invalidLedgersRejected(String mutation) throws Exception {
        Map<String, Object> row = entry(), data = ledger(List.of(row)); Object value = data;
        switch (mutation) {
            case "version" -> data.put("version", 2); case "environment" -> data.put("environment", "production");
            case "entries" -> data.put("entries", "invalid"); case "duplicate" -> data.put("entries", List.of(row, row));
            case "sha256" -> row.put("sha256", "invalid"); case "seed_file" -> row.put("seed_file", "unknown.sql");
            case "status" -> row.put("status", List.of()); case "incomplete" -> row.remove("started_at");
            case "entry_environment" -> row.put("environment", "production"); case "root" -> value = List.of();
        }
        Path path = writeLedger(value); assertThrows(IllegalArgumentException.class, () -> CatalogSeed.loadLedger(path, "staging"));
    }
    Path seeds() throws Exception {
        Path seeds = Files.createDirectory(root.resolve("seeds"));
        for (String name : CatalogSeed.FILES) Files.writeString(seeds.resolve(name), "-- TODO: approved catalog records\n"); return seeds;
    }
    record Result(boolean passed, String output) {}
    Result inspect(Path seeds, Map<String, Map<String, Object>> ledger) throws Exception {
        var bytes = new ByteArrayOutputStream(); boolean passed = CatalogSeed.inspect(seeds, ledger, false, new PrintStream(bytes)); return new Result(passed, bytes.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void templatesAndReadOnlyPlanning() throws Exception {
        Path seeds = seeds(); Result templates = inspect(seeds, Map.of()); assertTrue(templates.passed());
        assertEquals(8, templates.output().split("Status: TEMPLATE_ONLY", -1).length - 1);
        Path first = seeds.resolve(CatalogSeed.FILES.getFirst()); Files.writeString(first, "INSERT INTO categories VALUES ('Books');");
        byte[] before = Files.readAllBytes(first); Result planned = inspect(seeds, Map.of());
        assertTrue(planned.passed()); assertTrue(planned.output().contains("NOT_APPLIED")); assertTrue(planned.output().contains("NO DATABASE CONNECTION")); assertArrayEquals(before, Files.readAllBytes(first));
    }
    @ParameterizedTest @ValueSource(strings = {"checksum", "pending", "template_applied", "unsafe", "applied"})
    void ledgerReconciliation(String mode) throws Exception {
        Path seeds = seeds(), first = seeds.resolve(CatalogSeed.FILES.getFirst());
        if (!mode.equals("template_applied")) Files.writeString(first, mode.equals("unsafe") ? "DROP TABLE categories;" : "INSERT INTO categories VALUES ('Books');");
        Map<String, Object> row = entry(); row.put("sha256", mode.equals("checksum") ? "0".repeat(64) : CatalogSeed.checksum(Files.readAllBytes(first)));
        if (mode.equals("pending")) row.put("status", "EXECUTING");
        Result result = inspect(seeds, Map.of(CatalogSeed.FILES.getFirst(), row)); assertEquals(mode.equals("applied"), result.passed());
        assertTrue(result.output().contains(switch (mode) { case "checksum" -> "CHECKSUM_MISMATCH"; case "pending" -> "REQUIRES_REVIEW"; case "template_applied" -> "TEMPLATE_MARKED_APPLIED"; case "unsafe" -> "SQL_POLICY_VIOLATION"; default -> "Status: APPLIED"; }));
    }
    @Test void rejectSymlinksEmptyFilesAndUnexpectedInventory() throws Exception {
        Path seeds = seeds(), first = seeds.resolve(CatalogSeed.FILES.getFirst());
        Files.writeString(first, ""); assertThrows(IllegalArgumentException.class, () -> inspect(seeds, Map.of()));
        Files.delete(first); Files.createSymbolicLink(first, writeLedger(ledger(List.of()))); assertThrows(IllegalArgumentException.class, () -> inspect(seeds, Map.of()));
        Files.delete(first); Files.writeString(first, "-- template"); Files.writeString(seeds.resolve("999_extra.sql"), "-- extra");
        assertThrows(IllegalArgumentException.class, () -> inspect(seeds, Map.of()));
    }
    @Test void ledgerSymlinkRejected() throws Exception {
        Path original = writeLedger(ledger(List.of())), link = root.resolve("linked.json"); Files.createSymbolicLink(link, original);
        assertThrows(IllegalArgumentException.class, () -> CatalogSeed.loadLedger(link, "staging"));
    }
}
