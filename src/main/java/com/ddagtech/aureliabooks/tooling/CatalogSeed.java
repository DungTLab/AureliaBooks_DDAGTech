package com.ddagtech.aureliabooks.tooling;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import static com.ddagtech.aureliabooks.tooling.ToolJson.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Static SQL policy and read-only ledger planner. No JDBC or SQL execution. */
public final class CatalogSeed {
    public static final List<String> FILES = List.of("001_categories.sql", "002_publishers.sql", "003_authors.sql", "004_brands.sql",
            "005_products.sql", "006_books.sql", "007_book_authors.sql", "008_stationeries.sql");
    private static final Pattern FORBIDDEN = Pattern.compile("\\b(?:ALTER|CREATE|DROP|TRUNCATE|DELETE|UPDATE|REPLACE|GRANT|REVOKE|CALL|EXECUTE|PREPARE|DEALLOCATE|LOAD|OUTFILE|DUMPFILE|SLEEP|BENCHMARK|SELECT|LOAD_FILE)\\b", Pattern.CASE_INSENSITIVE);
    public static String mask(String sql) {
        char[] out = sql.toCharArray(); int i = 0;
        while (i < sql.length()) {
            int start = i;
            if (sql.charAt(i) == '#' || sql.startsWith("--", i) && (i + 2 == sql.length() || Character.isWhitespace(sql.charAt(i + 2)))) {
                int end = sql.indexOf('\n', i); i = end < 0 ? sql.length() : end;
            } else if (sql.startsWith("/*", i)) {
                require(!sql.startsWith("/*!", i) && !sql.startsWith("/*+", i), "EXECUTABLE_SQL_COMMENT");
                int end = sql.indexOf("*/", i + 2); require(end >= 0, "UNCLOSED_COMMENT"); i = end + 2;
            } else if ("'\"`".indexOf(sql.charAt(i)) >= 0) {
                char quote = sql.charAt(i++); boolean closed = false;
                while (i < sql.length()) {
                    if (sql.charAt(i) == '\\') i += 2;
                    else if (sql.charAt(i) == quote) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) i += 2;
                        else { i++; closed = true; break; }
                    } else i++;
                }
                require(closed, "UNCLOSED_QUOTE");
            } else { i++; continue; }
            for (int k = start; k < Math.min(i, out.length); k++) if (out[k] != '\n') out[k] = ' ';
        }
        return new String(out);
    }
    public static String checkSql(String filename, String sql) {
        require(FILES.contains(filename), "UNKNOWN_SEED"); String code = mask(sql).strip();
        if (code.isEmpty()) return "TEMPLATE_ONLY";
        require(code.endsWith(";"), "MISSING_SEMICOLON"); int count = 0;
        String table = filename.substring(4, filename.length() - 4);
        for (String raw : code.split(";")) {
            String statement = raw.strip(); if (statement.isEmpty()) continue;
            var insert = Pattern.compile("^INSERT\\s+INTO\\s+([a-z_][a-z0-9_]*)\\b", Pattern.CASE_INSENSITIVE).matcher(statement);
            require(insert.find(), "INSERT_ONLY"); require(insert.group(1).equalsIgnoreCase(table), "WRONG_TABLE");
            require(!FORBIDDEN.matcher(statement).find(), "DISALLOWED_SQL_KEYWORD"); count++;
        }
        return "INSERT_ONLY (" + count + " statements)";
    }
    public static Map<String, Map<String, Object>> loadLedger(Path path, String environment) throws Exception {
        require(Set.of("staging", "production").contains(environment), "INVALID_ENVIRONMENT");
        require(Files.isRegularFile(path, NOFOLLOW_LINKS), "INVALID_LEDGER");
        Map<String, Object> data = object(parse(Files.readString(path)));
        require(new java.math.BigDecimal("1").equals(data.get("version")), "LEDGER_VERSION");
        require(environment.equals(data.get("environment")), "LEDGER_ENVIRONMENT");
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Object raw : array(data.get("entries"))) {
            Map<String, Object> row = object(raw);
            require(row.keySet().containsAll(Set.of("seed_file", "sha256", "git_commit", "environment", "status", "started_at", "completed_at")), "INCOMPLETE_ENTRY");
            require(row.get("seed_file") instanceof String name && FILES.contains(name), "UNKNOWN_SEED");
            String name = (String)row.get("seed_file"); require(!rows.containsKey(name), "DUPLICATE_ENTRY");
            require(row.get("sha256") instanceof String checksum && checksum.matches("[a-f0-9]{64}"), "INVALID_CHECKSUM");
            require(row.get("status") instanceof String status && Set.of("PENDING", "EXECUTING", "APPLIED", "NEEDS_REVIEW").contains(status), "INVALID_STATUS");
            require(environment.equals(row.get("environment")), "ENTRY_ENVIRONMENT"); rows.put(name, row);
        }
        return rows;
    }
    public static String checksum(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static boolean inspect(Path seeds, Map<String, Map<String, Object>> ledger, boolean policyOnly, java.io.PrintStream out) throws Exception {
        try (var paths = Files.list(seeds)) {
            require(paths.filter(p -> p.toString().endsWith(".sql")).map(p -> p.getFileName().toString()).sorted().toList()
                    .equals(FILES.stream().sorted().toList()), "SEED_INVENTORY_MISMATCH");
        }
        boolean blocked = false;
        for (String name : FILES) {
            Path path = seeds.resolve(name); require(Files.isRegularFile(path, NOFOLLOW_LINKS), "INVALID_SEED_FILE");
            byte[] bytes = Files.readAllBytes(path); require(bytes.length > 0, "EMPTY_SEED");
            String sql = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            String hash = checksum(bytes), status;
            try { status = checkSql(name, sql); }
            catch (IllegalArgumentException e) { out.println(name + ": BLOCKED: SQL_POLICY_VIOLATION"); blocked = true; continue; }
            if (!policyOnly) {
                Map<String, Object> row = ledger.get(name);
                if (row != null) {
                    status = !hash.equals(row.get("sha256")) ? "BLOCKED: CHECKSUM_MISMATCH"
                            : !"APPLIED".equals(row.get("status")) ? "BLOCKED: REQUIRES_REVIEW"
                            : status.equals("TEMPLATE_ONLY") ? "BLOCKED: TEMPLATE_MARKED_APPLIED" : "APPLIED";
                } else if (!status.equals("TEMPLATE_ONLY")) status = "NOT_APPLIED";
            }
            blocked |= status.startsWith("BLOCKED"); out.println(name + "\n  SHA256: " + hash + "\n  Status: " + status);
        }
        out.println("NO SQL EXECUTED\nNO DATABASE CONNECTION\nNOTE: SQL syntax and dependencies not validated");
        return !blocked;
    }
    public static void main(String[] args) {
        try {
            boolean policy = Arrays.equals(args, new String[]{"--policy"});
            require(policy || args.length == 5 && args[0].equals("--dry-run") && args[1].equals("--environment") && args[3].equals("--ledger"), "DRY_RUN_ARGUMENTS_REQUIRED");
            Map<String, Map<String, Object>> ledger = policy ? Map.of() : loadLedger(Path.of(args[4]), args[2]);
            require(inspect(Path.of("database/seeds"), ledger, policy, System.out), "LEDGER_RECONCILIATION_REQUIRED");
            System.out.println("PASS: Static/read-only inspection completed");
        } catch (Exception e) { System.err.println("BLOCKED: CATALOG_SEED_REVIEW_REQUIRED"); System.exit(1); }
    }
}
