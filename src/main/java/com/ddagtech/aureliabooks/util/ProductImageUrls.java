package com.ddagtech.aureliabooks.util;

import java.net.URI;
import java.util.regex.Pattern;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;

/** One canonical database URL; never accepts object keys or arbitrary local paths. */
public final class ProductImageUrls {
    public static final String PREFIX = "/products/images/";
    private static final String UUID_V4 = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final Pattern INTERNAL = Pattern.compile(PREFIX + UUID_V4);
    private ProductImageUrls() { }

    public static boolean isInternal(String value) {
        return value != null && INTERNAL.matcher(value).matches();
    }

    public static String fromObjectKey(String key) {
        if (key == null || !key.startsWith("products/") || !key.endsWith(".png")) {
            throw new IllegalStateException("Unexpected product image key");
        }
        String url = PREFIX + key.substring("products/".length(), key.length() - 4);
        if (!isInternal(url)) throw new IllegalStateException("Unexpected product image key");
        return url;
    }

    public static String normalize(String value) {
        return value == null ? null : value.strip();
    }

    public static void validate(String value) {
        if (value == null || value.isEmpty()) return;
        if (value.length() > 500 || value.chars().anyMatch(c -> Character.isISOControl(c) || c == 92)) {
            throw invalid();
        }
        if (isInternal(value)) return;
        try {
            URI uri = URI.create(value);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && !uri.getHost().toLowerCase(java.util.Locale.ROOT).endsWith(".r2.cloudflarestorage.com")) return;
        } catch (IllegalArgumentException ignored) { }
        throw invalid();
    }

    private static AppException invalid() {
        return new AppException(ErrorCode.INVALID_INPUT_DATA,
                "Ảnh phải là URL HTTP/HTTPS hoặc đường dẫn ảnh đã tải lên hợp lệ.");
    }
}
