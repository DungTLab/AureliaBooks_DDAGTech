package com.ddagtech.aureliabooks.runtimeconfig;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class RuntimeConfigFixtures {
    static final String SECRET = "SYNTHETIC_RUNTIME_SECRET_" + UUID.randomUUID();
    static final String FIRST = "1".repeat(32), SECOND = "2".repeat(32), REVISION = "d".repeat(40);
    static Map<String, String> values(String group) {
        return switch (group) {
            case "google" -> Map.of("GOOGLE_CLIENT_ID", "synthetic-client", "GOOGLE_CLIENT_SECRET", SECRET);
            case "mail" -> Map.of("MAIL_HOST", "smtp.example.invalid", "MAIL_PORT", "587", "MAIL_USERNAME", "synthetic@example.invalid",
                    "MAIL_PASSWORD", SECRET, "MAIL_AUTH", "true", "MAIL_STARTTLS", "true", "MAIL_FROM", "synthetic@example.invalid");
            case "r2" -> Map.of("R2_ENABLED", "true", "R2_UPLOAD_ENABLED", "true",
                    "R2_ENDPOINT", "https://" + "0".repeat(32) + ".r2.cloudflarestorage.com",
                    "R2_BUCKET", "aureliabooks-staging-assets", "R2_ACCESS_KEY_ID", "synthetic-access-id",
                    "R2_SECRET_ACCESS_KEY", SECRET);
            default -> Map.of("VNPAY_TMN_CODE", "synthetic-merchant", "VNPAY_HASH_SECRET", SECRET,
                    "VNPAY_PAY_URL", "https://pay.example.invalid/pay", "VNPAY_RETURN_URL", "https://shop.example.invalid/return",
                    "VNPAY_API_URL", "https://api.example.invalid/query", "VNPAY_VERSION", "2.1.0", "VNPAY_COMMAND", "pay",
                    "VNPAY_ORDER_TYPE", "other", "VNPAY_EXPIRE_MINUTES", "15");
        };
    }
    static void absent(String needle, String value) { assertFalse(value.contains(needle), "UNEXPECTED_OR_SECRET_VALUE"); }
    static String report(Throwable error) {
        StringWriter output = new StringWriter(); error.printStackTrace(new PrintWriter(output)); return output.toString();
    }
    static Map<String, String> replacing(String group, String key, String value) {
        Map<String, String> values = new HashMap<>(values(group)); values.put(key, value); return values;
    }
}
