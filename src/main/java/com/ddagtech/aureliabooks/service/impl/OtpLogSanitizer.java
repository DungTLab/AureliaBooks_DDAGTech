package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.exception.AppException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats strictly allowlisted OTP delivery failure summaries (exception types and checked numeric status codes)
 * without emitting raw provider messages, recipient identifiers, OTP values, or credentials.
 */
final class OtpLogSanitizer {

    private static final int MAX_CAUSE_DEPTH = 10;

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\r\\n\\t\\p{Cntrl}]+");
    private static final Pattern AUTH_HEADER_OR_SCHEME = Pattern.compile(
            "(?i)\\b(?:(?:proxy-)?authorization\\s*[:=]\\s*(?:(?:bearer|basic|digest|token)\\s+)?|(?:bearer|basic)\\s+)\\S+"
    );
    private static final Pattern URL_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://\\S+");
    private static final Pattern EMAIL_TOKEN = Pattern.compile("\\S*@\\S+");
    private static final Pattern HOST_PORT_TOKEN = Pattern.compile("\\b[A-Za-z0-9.-]+:\\d{1,5}\\b");
    private static final Pattern SECRET_OR_OTP_PAIR = Pattern.compile(
            "(?i)\\b(?:(?:password|passwd|pwd|secret|token|api[-_]?key|credential(?:s)?|otp|pin|verification[-_]?code)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|\\S+)|code\\s*[:=]\\s*(?:\"\\d{4,}\"|'\\d{4,}'|\\d{4,}))"
    );
    private static final String ALLOWLISTED_STATUS_CODE_GROUP =
            "(40[0-9]|41[0-8]|42[1-689]|431|45[0-5]|50[0-9]|51[01]|52[0-7]|53[045]|55[0-6])";
    private static final String STATUS_KEY_PATTERN =
            "(?:(?:smtp|http|status|reply|error)[-_]?(?:status|code|reply)|smtp|http|status|reply|error|code)";
    private static final Pattern PHONE_TOKEN = Pattern.compile(
            "(?<!\\d)(?:\\+\\d{1,3}[\\s.-]?)?\\d(?:[\\s.-]?\\d){8,14}(?!\\d)"
    );
    private static final Pattern CONTEXTUAL_STATUS_CODE_NO_SPACE = Pattern.compile(
            "(?i)(^|[\\s(\\[{\",;])(" + STATUS_KEY_PATTERN + ")(?:\")?\\s*:\\s*"
                    + ALLOWLISTED_STATUS_CODE_GROUP
                    + "(?=$|[\\s.,;\\])}\"])"
    );
    private static final Pattern CHECKED_CODE_WITH_CONTEXT = Pattern.compile(
            "(?i)(?:^|[\\s(\\[{\",;])" + STATUS_KEY_PATTERN + "(?:\")?\\s*[:=]\\s*"
                    + ALLOWLISTED_STATUS_CODE_GROUP
                    + "(?:[\\s-]([245]\\.\\d{1,3}\\.\\d{1,3}))?(?=$|[\\s.,;\\])}\"])"
    );
    private static final Pattern CHECKED_SMTP_ENHANCED_CODE = Pattern.compile(
            "(?:^|\\s|\\(|\\[)(421|429|45[0-5]|50[0-5]|52[13]|53[045]|55[0-6])[\\s-]([245]\\.\\d{1,3}\\.\\d{1,3})(?=$|[\\s.,;\\])])"
    );
    private static final Pattern CHECKED_SMTP_BASE_CODE = Pattern.compile(
            "(?:^|\\s|\\(|\\[)(421|45[0-5]|50[0-5]|52[13]|53[045]|55[0-6])(?=$|[\\s.,;\\])])"
    );
    private static final Pattern STRICT_ALLOWLISTED_CODE = Pattern.compile(
            "^" + ALLOWLISTED_STATUS_CODE_GROUP + "(?: [245]\\.\\d{1,3}\\.\\d{1,3})?$"
    );

    private OtpLogSanitizer() {
    }

    static String summarizeFailure(Throwable ex) {
        if (ex == null) {
            return "UnknownFailure";
        }
        Throwable root = rootCauseOf(ex);
        String typeSummary = ex.getClass().getSimpleName();
        if (root != null && root != ex && !root.getClass().equals(ex.getClass())) {
            typeSummary = typeSummary + " -> " + root.getClass().getSimpleName();
        }
        String checkedCode = extractCheckedCode(ex);
        if (checkedCode == null || checkedCode.isBlank()) {
            return typeSummary;
        }
        return typeSummary + " (" + checkedCode + ")";
    }

    private static Throwable rootCauseOf(Throwable ex) {
        Throwable current = ex;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        while (current.getCause() != null && seen.add(current) && depth < MAX_CAUSE_DEPTH) {
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return current;
    }

    private static String extractCheckedCode(Throwable rootEx) {
        Deque<Throwable> queue = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        queue.add(rootEx);
        int examined = 0;
        while (!queue.isEmpty() && examined < 20) {
            Throwable current = queue.poll();
            if (current == null || !visited.add(current)) {
                continue;
            }
            examined++;
            if (current instanceof AppException appEx && appEx.getErrorCode() != null) {
                return "errorCode=" + appEx.getErrorCode().name();
            }
            String numericReply = extractNumericReturnCode(current);
            if (numericReply != null) {
                return "code=" + numericReply;
            }
            String messageCode = extractAllowlistedCodeFromMessage(current.getMessage());
            if (messageCode != null) {
                return "code=" + messageCode;
            }
            if (current.getCause() != null) {
                queue.add(current.getCause());
            }
            enqueueMailNestedExceptions(current, queue);
        }
        return null;
    }

    private static String extractNumericReturnCode(Throwable ex) {
        String packageName = ex.getClass().getPackageName();
        if (!packageName.startsWith("org.eclipse.angus.mail.") && !packageName.startsWith("com.sun.mail.")) {
            return null;
        }
        try {
            Method method = ex.getClass().getMethod("getReturnCode");
            Object result = method.invoke(ex);
            if (result instanceof Integer code && code >= 400 && code <= 599) {
                return Integer.toString(code);
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
        return null;
    }

    private static void enqueueMailNestedExceptions(Throwable ex, Deque<Throwable> queue) {
        try {
            Method method = ex.getClass().getMethod("getFailedMessages");
            Object mapObj = method.invoke(ex);
            if (mapObj instanceof java.util.Map<?, ?> map) {
                for (Object value : map.values()) {
                    if (value instanceof Throwable nested) {
                        queue.add(nested);
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Not a Spring MailSendException or nested map unavailable.
        }
    }

    static String extractAllowlistedCodeFromMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return null;
        }
        // Scrub credentials, URLs, emails, OTP/secret pairs, and phones first; then preserve spacing on
        // allowlisted contextual status fields before stripping host:port tokens (e.g., relay.invalid:587).
        String scrubbed = CONTROL_CHARS.matcher(rawMessage).replaceAll(" ");
        scrubbed = AUTH_HEADER_OR_SCHEME.matcher(scrubbed).replaceAll(" ");
        scrubbed = URL_TOKEN.matcher(scrubbed).replaceAll(" ");
        scrubbed = EMAIL_TOKEN.matcher(scrubbed).replaceAll(" ");
        scrubbed = SECRET_OR_OTP_PAIR.matcher(scrubbed).replaceAll(" ");
        scrubbed = PHONE_TOKEN.matcher(scrubbed).replaceAll(" ");
        scrubbed = normalizeContextualStatusColon(scrubbed);
        scrubbed = HOST_PORT_TOKEN.matcher(scrubbed).replaceAll(" ");

        String match = matchCode(CHECKED_SMTP_ENHANCED_CODE, scrubbed, true);
        if (match == null) {
            match = matchCode(CHECKED_CODE_WITH_CONTEXT, scrubbed, true);
        }
        if (match == null) {
            match = matchCode(CHECKED_SMTP_BASE_CODE, scrubbed, false);
        }
        if (match != null && STRICT_ALLOWLISTED_CODE.matcher(match).matches()) {
            return match;
        }
        return null;
    }

    private static String normalizeContextualStatusColon(String text) {
        Matcher matcher = CONTEXTUAL_STATUS_CODE_NO_SPACE.matcher(text);
        StringBuilder normalized = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2) + ": " + matcher.group(3)
            ));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    private static String matchCode(Pattern pattern, String text, boolean withOptionalEnhanced) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String base = matcher.group(1);
        if (withOptionalEnhanced && matcher.groupCount() >= 2 && matcher.group(2) != null) {
            return base + " " + matcher.group(2);
        }
        return base;
    }
}
