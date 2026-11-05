package com.ddagtech.aureliabooks.runtimeconfig;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure JDK policy. No environment discovery, Spring initialization or network. */
public final class ConfigPolicy {
    private ConfigPolicy() {}
    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(code, null, false, false); }
    }
    public record Field(String scope, String target, String group) {}
    public static final Set<String> GROUPS = Set.of("google", "mail", "vnpay", "sms-demo");
    public static final Map<String, String> FLAGS = Map.of(
            "R2_ENABLED", "app.r2.enabled", "R2_UPLOAD_ENABLED", "app.r2.upload-enabled");
    public static final Set<String> RESERVED = Set.of("R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY");
    public static final Map<String, Field> FIELDS;
    static {
        Map<String, Field> fields = new LinkedHashMap<>();
        add(fields, "google", "ES", "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_ID");
        add(fields, "google", "ES", "GOOGLE_CLIENT_SECRET", "GOOGLE_CLIENT_SECRET");
        add(fields, "mail", "EV", "MAIL_HOST", "spring.mail.host");
        add(fields, "mail", "EV", "MAIL_PORT", "spring.mail.port");
        add(fields, "mail", "ES", "MAIL_USERNAME", "spring.mail.username");
        add(fields, "mail", "ES", "MAIL_PASSWORD", "spring.mail.password");
        add(fields, "mail", "EV", "MAIL_AUTH", "spring.mail.properties.mail.smtp.auth");
        add(fields, "mail", "EV", "MAIL_STARTTLS", "spring.mail.properties.mail.smtp.starttls.enable");
        add(fields, "mail", "EV", "MAIL_FROM", "app.contact-verification.mail-from");
        add(fields, "sms-demo", "EV", "DEMO_SMS_INBOX", "demo.sms.inbox");
        for (String name : List.of("TMN_CODE", "HASH_SECRET", "PAY_URL", "RETURN_URL", "API_URL",
                "VERSION", "COMMAND", "ORDER_TYPE", "EXPIRE_MINUTES")) {
            add(fields, "vnpay", Set.of("TMN_CODE", "HASH_SECRET").contains(name) ? "ES" : "EV",
                    "VNPAY_" + name, "vnpay." + name.toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        for (String name : List.of("ENDPOINT", "BUCKET", "UPLOAD_REQUESTS_PER_MINUTE", "UPLOAD_MAX_CONCURRENT")) {
            add(fields, "r2", "EV", "R2_" + name, "app.r2." + name.toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        FIELDS = Collections.unmodifiableMap(fields);
    }
    private static void add(Map<String, Field> fields, String group, String scope, String key, String target) {
        fields.put(key, new Field(scope, target, group));
    }
    public static void check(boolean condition, String code) { if (!condition) throw new Rejected(code); }
    public static void generation(String id, String revision) {
        check(id != null && id.matches("[0-9a-f]{32}"), "INVALID_GENERATION");
        check(revision != null && revision.matches("[0-9a-f]{40}"), "INVALID_REVISION");
    }
    private static void text(String value, String key) {
        check(value != null && !value.isEmpty() && value.codePointCount(0, value.length()) <= 4096,
                "INVALID_TEXT:" + key);
        check(!value.contains("\0") && !value.contains("${") && !value.contains("#{"),
                "UNSUPPORTED_INTERPOLATION_OR_NUL:" + key);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                check(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(++i)), "INVALID_UNICODE:" + key);
            } else check(!Character.isLowSurrogate(c), "INVALID_UNICODE:" + key);
        }
    }
    private static void https(String value, String key) {
        boolean valid = false;
        try {
            URI uri = new URI(value);
            valid = "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null && uri.getPort() <= 65535
                    && value.codePoints().noneMatch(Character::isWhitespace);
        } catch (Exception ignored) { /* Values/causes never enter diagnostics. */ }
        check(valid, "INVALID_HTTPS_URL:" + key);
    }
    public static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\', ' ', ':', '=', '#', '!' -> out.append('\\').append(c);
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 32 || c > 126) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
    public static byte[] render(Map<String, String> values, Collection<String> groups) {
        check(values != null && groups != null, "INVALID_INPUT");
        check(groups.stream().allMatch(g -> g != null && GROUPS.contains(g)), "INVALID_GROUP");
        check(!groups.contains("sms-demo") || groups.contains("mail"), "SMS_DEMO_REQUIRES_MAIL_GROUP");
        check(values.keySet().stream().allMatch(Objects::nonNull), "INVALID_KEY");
        check(values.keySet().stream().noneMatch(RESERVED::contains), "R2_CREDENTIAL_TRANSFER_BLOCKED");
        check(values.keySet().stream().allMatch(k -> FIELDS.containsKey(k) || FLAGS.containsKey(k)), "UNKNOWN_KEY");
        FLAGS.forEach((key, target) -> check("false".equals(values.getOrDefault(key, "false")),
                "R2_MUST_REMAIN_DISABLED:" + key));
        FIELDS.forEach((key, field) -> {
            if (groups.contains(field.group())) check(values.containsKey(key), "INCOMPLETE_PROVIDER_GROUP");
        });
        TreeMap<String, String> result = new TreeMap<>();
        FLAGS.values().forEach(key -> result.put(key, "false"));
        for (String key : new TreeSet<>(values.keySet())) {
            if (FLAGS.containsKey(key)) continue;
            Field field = FIELDS.get(key);
            check(field.group().equals("r2") || groups.contains(field.group()), "UNDECLARED_PROVIDER_GROUP");
            String value = values.get(key);
            text(value, key);
            if (key.endsWith("_URL") || key.equals("R2_ENDPOINT")) https(value, key);
            if (Set.of("MAIL_AUTH", "MAIL_STARTTLS").contains(key))
                check(Set.of("true", "false").contains(value), "INVALID_BOOLEAN:" + key);
            if (Set.of("MAIL_PORT", "VNPAY_EXPIRE_MINUTES", "R2_UPLOAD_REQUESTS_PER_MINUTE", "R2_UPLOAD_MAX_CONCURRENT").contains(key)) {
                check(value.matches("[1-9][0-9]{0,4}"), "INVALID_NUMBER:" + key);
                check(Integer.parseInt(value) <= (key.equals("MAIL_PORT") ? 65535 : 10000), "INVALID_NUMBER:" + key);
            }
            switch (key) {
                case "MAIL_HOST" -> check(value.matches("[A-Za-z0-9.-]{1,253}"), "INVALID_MAIL_HOST");
                case "MAIL_FROM" -> check(value.matches("[A-Za-z0-9._+\\-]+@[A-Za-z0-9.-]+"), "INVALID_MAIL_FROM");
                case "DEMO_SMS_INBOX" -> check(value.matches("[A-Za-z0-9._+\\-]+@[A-Za-z0-9.-]+"), "INVALID_DEMO_SMS_INBOX");
                case "R2_ENDPOINT" -> check(value.matches("https://[0-9a-f]{32}\\.r2\\.cloudflarestorage\\.com"), "R2_ENDPOINT_POLICY");
                case "R2_BUCKET" -> check(value.equals("aureliabooks-staging-assets"), "R2_BUCKET_POLICY");
                default -> { }
            }
            result.put(field.target(), value);
        }
        if (groups.contains("mail")) {
            check("true".equals(values.get("MAIL_AUTH")) && "true".equals(values.get("MAIL_STARTTLS")),
                    "SMTP_POLICY_REQUIRES_AUTH_STARTTLS");
            result.put("spring.mail.properties.mail.smtp.starttls.required", "true");
        }
        StringBuilder payload = new StringBuilder();
        result.forEach((key, value) -> payload.append(key).append('=').append(escape(value)).append('\n'));
        byte[] encoded = payload.toString().getBytes(StandardCharsets.US_ASCII);
        check(encoded.length <= 65536, "PAYLOAD_TOO_LARGE");
        return encoded;
    }
    public static void precedence(Collection<String> names, Collection<String> jvm, Collection<String> args) {
        check(jvm.isEmpty() && args.isEmpty(), "UNVERIFIED_PROCESS_OVERRIDES");
        check(Set.of("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASS", "MYSQL_ROOT_PASSWORD",
                "MYSQL_USER", "MYSQL_PASSWORD", "APP_IMAGE", "PORT").containsAll(names), "UNVERIFIED_ENVIRONMENT_KEYS");
    }
}
