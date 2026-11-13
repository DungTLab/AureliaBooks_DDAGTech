package com.ddagtech.aureliabooks.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared cryptographic OTP, sliding-window rate-limiter, and contact normalization helper
 * used by customer contact verification and internal staff recovery email flows.
 * Intentionally un-annotated so Spring proxy wiring tests remain unaffected.
 */
public final class ContactOtpEngine {

    public static final Duration TTL = Duration.ofMinutes(5);
    public static final Duration WINDOW = Duration.ofMinutes(15);
    public static final long COOLDOWN_SECONDS = 60L;
    public static final int MAX_ATTEMPTS = 5;
    public static final int MAX_BUCKETS = 10_000;

    public static final Set<String> ALLOWED_INTERNAL_ROLES = Set.of(
            "ROLE_ADMIN",
            "ROLE_MANAGER",
            "ROLE_SALE_STAFF"
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );
    private static final Pattern VN_PHONE_PATTERN = Pattern.compile("^0[35789][0-9]{8}$");
    private static final Pattern SIX_DIGIT_OTP_PATTERN = Pattern.compile("^\\d{6}$");

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final byte[] hmacKey = new byte[32];
    private final Map<String, Bucket> quotaBuckets = new HashMap<>();

    public record OtpToken(String code, byte[] mac) {
    }

    private static final class Bucket {
        int count;
        Instant resetAt;

        Bucket(Instant resetAt) {
            this.resetAt = resetAt;
        }
    }

    public ContactOtpEngine(Clock clock) {
        this.clock = clock;
        this.random.nextBytes(this.hmacKey);
    }

    public Clock clock() {
        return clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public OtpToken issueCode(String contextPrefix, byte[] previousMac) {
        String code;
        byte[] mac;
        do {
            code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
            mac = hmac(contextPrefix + "|" + code);
        } while (previousMac != null && MessageDigest.isEqual(mac, previousMac));
        return new OtpToken(code, mac);
    }

    public boolean matches(String contextPrefix, String rawCode, byte[] expectedMac) {
        if (rawCode == null || expectedMac == null) {
            return false;
        }
        String normalized = rawCode.trim();
        if (!SIX_DIGIT_OTP_PATTERN.matcher(normalized).matches()) {
            return false;
        }
        return MessageDigest.isEqual(hmac(contextPrefix + "|" + normalized), expectedMac);
    }

    /**
     * Atomically verifies and consumes one unit across every key in the group within a 15-minute window.
     */
    public boolean consumeAllQuotas(List<String> keys, int limit) {
        if (keys == null || keys.isEmpty() || limit <= 0) {
            return false;
        }
        Instant now = clock.instant();
        synchronized (quotaBuckets) {
            quotaBuckets.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().resetAt));
            if (quotaBuckets.size() + keys.size() > MAX_BUCKETS) {
                return false;
            }
            for (String key : keys) {
                if (key == null || key.isBlank()) {
                    return false;
                }
                Bucket existing = quotaBuckets.get(key);
                if (existing != null && existing.count >= limit) {
                    return false;
                }
            }
            for (String key : keys) {
                quotaBuckets.computeIfAbsent(key, ignored -> new Bucket(now.plus(WINDOW))).count++;
            }
            return true;
        }
    }

    public long remainingCooldownSeconds(Instant lastSent) {
        if (lastSent == null) {
            return 0L;
        }
        return Math.max(0L, COOLDOWN_SECONDS - Duration.between(lastSent, clock.instant()).getSeconds());
    }

    public boolean isInCooldown(Instant lastSent) {
        return remainingCooldownSeconds(lastSent) > 0L;
    }

    public long remainingExpirySeconds(Instant expiresAt) {
        if (expiresAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(clock.instant(), expiresAt).getSeconds());
    }

    public boolean isExpired(Instant expiresAt) {
        return expiresAt == null || !clock.instant().isBefore(expiresAt);
    }

    public byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    public static String normalizeEmail(String rawEmail) {
        if (rawEmail == null) {
            return null;
        }
        String trimmed = rawEmail.trim();
        return trimmed.isEmpty() ? "" : trimmed.toLowerCase(Locale.ROOT);
    }

    public static boolean isValidEmail(String normalizedEmail) {
        return normalizedEmail != null
                && !normalizedEmail.isEmpty()
                && normalizedEmail.length() <= 100
                && EMAIL_PATTERN.matcher(normalizedEmail).matches();
    }

    public static String normalizePhone(String rawPhone) {
        if (rawPhone == null) {
            return null;
        }
        String trimmed = rawPhone.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.startsWith("+84") ? "0" + trimmed.substring(3) : trimmed;
    }

    public static boolean isValidVietnamPhone(String normalizedPhone) {
        return normalizedPhone != null && VN_PHONE_PATTERN.matcher(normalizedPhone).matches();
    }

    public static String maskEmail(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    public static String maskPhone(String phone) {
        if (phone == null) {
            return "******";
        }
        return "******" + phone.substring(Math.max(0, phone.length() - 4));
    }

    public static String maskContact(String value) {
        if (value == null) {
            return "";
        }
        return value.indexOf('@') >= 0 ? maskEmail(value) : maskPhone(value);
    }
}
