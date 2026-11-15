package com.ddagtech.aureliabooks.service.impl;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts recipient contact details, OTP codes, and credentials from delivery failure logs.
 */
final class OtpLogSanitizer {

    private static final int MAX_DETAIL_LENGTH = 200;
    private static final int MAX_CAUSE_DEPTH = 10;

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\r\\n\\t\\p{Cntrl}]+");
    private static final Pattern MULTI_SPACES = Pattern.compile("\\s{2,}");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("://[^/\\s@]+:[^/\\s@]+@");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+(?:\\.[A-Za-z]{2,})?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ANGLE_EMAIL_PATTERN = Pattern.compile("<[^<>\\s@]+@[^<>\\s]+>");
    private static final Pattern SECRET_PAIR_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[-_]?key|authorization|auth|credential(?:s)?)\\s*([=:])\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)"
    );
    private static final Pattern AUTH_SCHEME_PATTERN = Pattern.compile(
            "(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9\\-._~+/]+=*"
    );
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\+\\d{1,3}[\\s.-]?)?\\d(?:[\\s.-]?\\d){8,14}(?!\\d)"
    );

    private OtpLogSanitizer() {
    }

    static String sanitizeFailure(Throwable ex, String otpCode, String... sensitiveLiterals) {
        if (ex == null) {
            return "UnknownFailure";
        }
        Throwable root = rootCauseOf(ex);
        String typeSummary = ex.getClass().getSimpleName();
        if (root != ex && root != null && !root.getClass().equals(ex.getClass())) {
            typeSummary = typeSummary + " -> " + root.getClass().getSimpleName();
        }

        String rawDetail = combineMessages(ex, root);
        String sanitized = redactDetail(rawDetail, otpCode, sensitiveLiterals);
        if (sanitized.isBlank()) {
            return typeSummary;
        }
        return typeSummary + " - " + sanitized;
    }

    private static Throwable rootCauseOf(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current.getCause() != null && current.getCause() != current && depth < MAX_CAUSE_DEPTH) {
            current = current.getCause();
            depth++;
        }
        return current;
    }

    private static String combineMessages(Throwable ex, Throwable root) {
        String primary = ex.getMessage();
        String rootMsg = root != null ? root.getMessage() : null;
        if (primary == null || primary.isBlank()) {
            return rootMsg == null ? "" : rootMsg;
        }
        if (root == ex || rootMsg == null || rootMsg.isBlank()) {
            return primary;
        }
        if (primary.toLowerCase(Locale.ROOT).contains(rootMsg.toLowerCase(Locale.ROOT))) {
            return primary;
        }
        return primary + " | " + rootMsg;
    }

    static String redactDetail(String rawDetail, String otpCode, String... sensitiveLiterals) {
        if (rawDetail == null || rawDetail.isBlank()) {
            return "";
        }
        String text = CONTROL_CHARS.matcher(rawDetail).replaceAll(" ");

        if (otpCode != null && !otpCode.isBlank()) {
            text = text.replace(otpCode, "[REDACTED_OTP]");
        }

        text = URL_CREDENTIALS.matcher(text).replaceAll("://[REDACTED]@");
        text = SECRET_PAIR_PATTERN.matcher(text).replaceAll("$1$2[REDACTED]");
        text = AUTH_SCHEME_PATTERN.matcher(text).replaceAll("$1 [REDACTED]");
        text = ANGLE_EMAIL_PATTERN.matcher(text).replaceAll("<[REDACTED_EMAIL]>");
        text = EMAIL_PATTERN.matcher(text).replaceAll("[REDACTED_EMAIL]");
        text = PHONE_PATTERN.matcher(text).replaceAll("[REDACTED_PHONE]");
        if (sensitiveLiterals != null) {
            for (String literal : sensitiveLiterals) {
                if (literal == null || literal.isBlank()) {
                    continue;
                }
                text = Pattern.compile(Pattern.quote(literal), Pattern.CASE_INSENSITIVE)
                        .matcher(text)
                        .replaceAll(Matcher.quoteReplacement("[REDACTED_CONTACT]"));
            }
        }
        text = MULTI_SPACES.matcher(text).replaceAll(" ").trim();

        if (text.length() > MAX_DETAIL_LENGTH) {
            return text.substring(0, MAX_DETAIL_LENGTH - 3) + "...";
        }
        return text;
    }
}
