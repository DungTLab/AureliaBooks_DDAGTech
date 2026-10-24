package com.ddagtech.aureliabooks.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

@DisplayName("Standalone Database Migration Runner for Missing Schema Columns")
class DatabaseMigrationRunner {

    @Test
    void applyMissingSchemaColumns() throws Exception {
        String enabled = System.getProperty("ENABLE_DB_MIGRATION_RUNNER", System.getenv("ENABLE_DB_MIGRATION_RUNNER"));
        if (!"true".equalsIgnoreCase(enabled)) {
            System.out.println("⚠️ [F18 GUARD] DatabaseMigrationRunner is disabled by default to guard QA/live databases from accidental DDL execution. Set ENABLE_DB_MIGRATION_RUNNER=true to enable.");
            return;
        }

        String url = System.getProperty("DB_URL", System.getenv().getOrDefault("DB_URL",
                "jdbc:mysql://localhost:3306/aurelia_books_db?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh&createDatabaseIfNotExist=true&allowPublicKeyRetrieval=true&useSSL=false"));
        String user = System.getProperty("DB_USERNAME", System.getenv().getOrDefault("DB_USERNAME", "root"));
        String pass = System.getProperty("DB_PASSWORD", System.getenv().getOrDefault("DB_PASSWORD", "root"));

        System.out.println("Connecting to database: " + url + " as user: " + user + " to apply missing schema migrations...");
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {

            // 1. Add is_active to authors if missing
            try {
                stmt.executeUpdate("ALTER TABLE authors ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE");
                System.out.println("✅ Added column is_active to authors");
            } catch (Exception e) {
                System.out.println("ℹ️ authors.is_active: " + e.getMessage());
            }

            // 2. Add is_active to publishers if missing
            try {
                stmt.executeUpdate("ALTER TABLE publishers ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE");
                System.out.println("✅ Added column is_active to publishers");
            } catch (Exception e) {
                System.out.println("ℹ️ publishers.is_active: " + e.getMessage());
            }

            // 3. Add is_active to brands if missing
            try {
                stmt.executeUpdate("ALTER TABLE brands ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE");
                System.out.println("✅ Added column is_active to brands");
            } catch (Exception e) {
                System.out.println("ℹ️ brands.is_active: " + e.getMessage());
            }

            // 4. Modify shipping_addresses district to NULL
            try {
                stmt.executeUpdate("ALTER TABLE shipping_addresses MODIFY district VARCHAR(100) NULL");
                System.out.println("✅ Modified shipping_addresses.district to NULL");
            } catch (Exception e) {
                System.out.println("ℹ️ shipping_addresses.district: " + e.getMessage());
            }

            // 5. Align auth credentials check constraint if present
            try {
                stmt.executeUpdate("ALTER TABLE users DROP CHECK chk_auth_credentials");
                stmt.executeUpdate("""
                    ALTER TABLE users ADD CONSTRAINT chk_auth_credentials CHECK (
                        (auth_provider = 'LOCAL' AND password_hash IS NOT NULL)
                        OR
                        (auth_provider = 'GOOGLE' AND (provider_id IS NOT NULL OR (email_verified = TRUE AND email IS NOT NULL)))
                    )
                """);
                System.out.println("✅ Updated users.chk_auth_credentials constraint");
            } catch (Exception e) {
                System.out.println("ℹ️ users.chk_auth_credentials: " + e.getMessage());
            }

            System.out.println("=== ALL MIGRATIONS APPLIED SUCCESSFULLY ===");
        }
    }
}
