package com.ddagtech.aureliabooks.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Per-process upload admission, before CSRF can trigger multipart parameter parsing.
 * Authentication/authorization and CSRF still run normally after admission.
 */
public final class ProductImageUploadFilter extends OncePerRequestFilter {
    private static final long WINDOW_NANOS = 60_000_000_000L;
    private static final int MAX_IDENTITIES = 1024;
    private static final long MAX_REQUEST_BYTES = 6L * 1024 * 1024;
    private final boolean enabled;
    private final int requestsPerMinute;
    private final Semaphore slots;
    private final LongSupplier ticker;
    private final Map<String, Window> windows = new HashMap<>();
    private record Window(long started, int count) { }

    public ProductImageUploadFilter(boolean enabled, int requestsPerMinute, int maxConcurrent) {
        this(enabled, requestsPerMinute, maxConcurrent, System::nanoTime);
    }

    ProductImageUploadFilter(boolean enabled, int requestsPerMinute, int maxConcurrent, LongSupplier ticker) {
        if (requestsPerMinute < 1 || maxConcurrent < 1) {
            throw new IllegalArgumentException("Upload limits must be positive");
        }
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
        this.slots = new Semaphore(maxConcurrent);
        this.ticker = ticker;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !"/manager/products/images".equals(PortalSecurity.path(request));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        if (!enabled) { reject(response, 503, "Tải ảnh đang tạm dừng.", 60); return; }
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
            reject(response, 413, "Dữ liệu tải ảnh vượt quá 6 MiB.", 0); return;
        }
        if (!slots.tryAcquire()) { reject(response, 429, "Đang có nhiều lượt tải ảnh. Vui lòng thử lại.", 1); return; }
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            // Never trust forwarded IP/user headers, and never allocate counters for anonymous identities.
            if (PortalSecurity.signedIn(authentication) && authentication.getAuthorities().stream()
                    .anyMatch(role -> "ROLE_MANAGER".equals(role.getAuthority()))
                    && !admit(authentication.getName())) {
                reject(response, 429, "Bạn đã tải ảnh quá nhiều lần. Vui lòng thử lại sau một phút.", 60);
                return;
            }
            chain.doFilter(request, response);
        } finally {
            slots.release();
        }
    }

    private synchronized boolean admit(String identity) {
        long now = ticker.getAsLong();
        windows.entrySet().removeIf(entry -> now - entry.getValue().started() >= WINDOW_NANOS);
        Window window = windows.get(identity);
        if (window == null) {
            // Fail closed instead of evicting live counters and permitting a quota bypass.
            if (windows.size() >= MAX_IDENTITIES) return false;
            windows.put(identity, new Window(now, 1));
            return true;
        }
        if (window.count() >= requestsPerMinute) return false;
        windows.put(identity, new Window(window.started(), window.count() + 1));
        return true;
    }

    private void reject(HttpServletResponse response, int status, String message, int retryAfter) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (retryAfter > 0) response.setHeader("Retry-After", Integer.toString(retryAfter));
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
