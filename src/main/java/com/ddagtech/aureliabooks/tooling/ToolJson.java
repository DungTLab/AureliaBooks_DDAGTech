package com.ddagtech.aureliabooks.tooling;

import java.util.*;

/** Bounded JSON data reader for standalone JDK tools; rejects duplicate keys. */
public final class ToolJson {
    private final String text;
    private int position;
    private ToolJson(String text) {
        require(text.length() <= 16 * 1024 * 1024, "JSON_TOO_LARGE"); this.text = text;
    }
    public static Object parse(String text) {
        ToolJson reader = new ToolJson(text); Object value = reader.value(0); reader.space();
        require(reader.position == text.length(), "TRAILING_JSON"); return value;
    }
    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    @SuppressWarnings("unchecked") public static Map<String, Object> object(Object value) {
        require(value instanceof Map, "EXPECTED_OBJECT"); return (Map<String, Object>)value;
    }
    @SuppressWarnings("unchecked") public static List<Object> array(Object value) {
        require(value instanceof List, "EXPECTED_ARRAY"); return (List<Object>)value;
    }
    private void space() { while (position < text.length() && " \r\n\t".indexOf(text.charAt(position)) >= 0) position++; }
    private char next() { require(position < text.length(), "INCOMPLETE_JSON"); return text.charAt(position++); }
    private Object value(int depth) {
        require(depth < 64, "JSON_NESTING"); space(); require(position < text.length(), "INCOMPLETE_JSON");
        char c = text.charAt(position);
        if (c == '"') return string();
        if (c == '{') {
            position++; space(); Map<String, Object> result = new LinkedHashMap<>();
            if (position < text.length() && text.charAt(position) == '}') { position++; return result; }
            do {
                space(); require(position < text.length() && text.charAt(position) == '"', "OBJECT_KEY");
                String key = string(); space(); require(next() == ':', "OBJECT_COLON");
                require(!result.containsKey(key), "DUPLICATE_KEY"); result.put(key, value(depth + 1)); space();
                char delimiter = next(); if (delimiter == '}') return result;
                require(delimiter == ',', "OBJECT_DELIMITER");
            } while (true);
        }
        if (c == '[') {
            position++; space(); List<Object> result = new ArrayList<>();
            if (position < text.length() && text.charAt(position) == ']') { position++; return result; }
            do {
                result.add(value(depth + 1)); space(); char delimiter = next(); if (delimiter == ']') return result;
                require(delimiter == ',', "ARRAY_DELIMITER");
            } while (true);
        }
        for (String literal : List.of("true", "false", "null")) {
            if (text.startsWith(literal, position)) {
                position += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        var number = java.util.regex.Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
                .matcher(text).region(position, text.length());
        require(number.lookingAt(), "INVALID_JSON_VALUE"); position = number.end();
        return new java.math.BigDecimal(number.group());
    }
    private String string() {
        require(next() == '"', "STRING_REQUIRED"); StringBuilder result = new StringBuilder();
        while (true) {
            char c = next(); if (c == '"') return result.toString(); require(c >= 32, "STRING_CONTROL");
            if (c != '\\') { result.append(c); continue; }
            char escaped = next();
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b'); case 'f' -> result.append('\f');
                case 'n' -> result.append('\n'); case 'r' -> result.append('\r'); case 't' -> result.append('\t');
                case 'u' -> {
                    require(position + 4 <= text.length(), "UNICODE_ESCAPE");
                    String hex = text.substring(position, position + 4);
                    require(hex.matches("[0-9a-fA-F]{4}"), "UNICODE_ESCAPE");
                    result.append((char)Integer.parseInt(hex, 16)); position += 4;
                }
                default -> throw new IllegalArgumentException("STRING_ESCAPE");
            }
        }
    }
    public static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else out.append(c);
            }
            return out.append('"').toString();
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>(); map.forEach((k, v) -> parts.add(write(k.toString()) + ":" + write(v)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Collection<?> list) return "[" + String.join(",", list.stream().map(ToolJson::write).toList()) + "]";
        throw new IllegalArgumentException("UNSUPPORTED_JSON_TYPE");
    }
}
