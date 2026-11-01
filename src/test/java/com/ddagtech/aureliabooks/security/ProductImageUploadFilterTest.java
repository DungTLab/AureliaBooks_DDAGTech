package com.ddagtech.aureliabooks.security;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

class ProductImageUploadFilterTest {
    private static final String API = "/manager/products/images";
    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }
    private void manager(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                name, "unused", AuthorityUtils.createAuthorityList("ROLE_MANAGER")));
    }
    private MockHttpServletResponse request(ProductImageUploadFilter filter) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", API), response, (a, b) -> {});
        return response;
    }
    @Test void quotaIsPerPrincipalAcrossRequestsAndExpiresWithoutSleep() throws Exception {
        var time = new AtomicLong();
        var filter = new ProductImageUploadFilter(true, 2, 1, time::get);
        manager("first");
        assertThat(request(filter).getStatus()).isEqualTo(200);
        assertThat(request(filter).getStatus()).isEqualTo(200);
        var denied = request(filter);
        assertThat(denied.getStatus()).isEqualTo(429);
        assertThat(denied.getHeader("Retry-After")).isEqualTo("60");
        assertThat(denied.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(denied.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        manager("second");
        assertThat(request(filter).getStatus()).isEqualTo(200);
        manager("first");
        time.set(60_000_000_000L);
        assertThat(request(filter).getStatus()).isEqualTo(200);
    }
    @Test void concurrentRequestsAreRejectedImmediatelyAndPermitIsReleased() throws Exception {
        var filter = new ProductImageUploadFilter(true, 10, 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(() -> {
                try {
                    filter.doFilter(new MockHttpServletRequest("POST", API), new MockHttpServletResponse(), (a, b) -> {
                        entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timeout"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    });
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(request(filter).getStatus()).isEqualTo(429);
            release.countDown();
            running.get(5, TimeUnit.SECONDS);
            assertThat(request(filter).getStatus()).isEqualTo(200);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void downstreamFailureDoesNotLeakPermit() throws Exception {
        var filter = new ProductImageUploadFilter(true, 10, 1);
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("POST", API),
                new MockHttpServletResponse(), (a, b) -> { throw new IOException("Mock failure"); }))
                .isInstanceOf(IOException.class);
        assertThat(request(filter).getStatus()).isEqualTo(200);
    }
    @Test void identityMapIsBoundedWithoutEvictingLiveCounters() throws Exception {
        var time = new AtomicLong();
        var filter = new ProductImageUploadFilter(true, 1, 1, time::get);
        for (int i = 0; i < 1024; i++) { manager("manager-" + i); assertThat(request(filter).getStatus()).isEqualTo(200); }
        manager("overflow");
        assertThat(request(filter).getStatus()).isEqualTo(429);
        manager("manager-0");
        assertThat(request(filter).getStatus()).isEqualTo(429);
        time.set(60_000_000_000L);
        assertThat(request(filter).getStatus()).isEqualTo(200);
    }
    @Test void declaredOversizeRejectedBeforeDownstream() throws Exception {
        var request = new MockHttpServletRequest("POST", API);
        request.setContent(new byte[6 * 1024 * 1024 + 1]);
        var response = new MockHttpServletResponse();
        new ProductImageUploadFilter(true, 10, 1).doFilter(request, response,
                (a, b) -> { throw new AssertionError("Must not parse request"); });
        assertThat(response.getStatus()).isEqualTo(413);
    }
    @Test void uploadPauseLeavesOtherRoutesAndMethodsUntouched() throws Exception {
        var filter = new ProductImageUploadFilter(false, 10, 1);
        assertThat(request(filter).getStatus()).isEqualTo(503);
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", API + "/id"), response, (a, b) -> b.getWriter().write("read"));
        assertThat(response.getContentAsString()).isEqualTo("read");
    }
    @Test void contextPathIsHandled() throws Exception {
        var request = new MockHttpServletRequest("POST", "/bookshop" + API);
        request.setContextPath("/bookshop");
        var response = new MockHttpServletResponse();
        new ProductImageUploadFilter(false, 10, 1).doFilter(request, response, (a, b) -> { throw new AssertionError(); });
        assertThat(response.getStatus()).isEqualTo(503);
    }
    @Test void invalidLimitsFailClosed() {
        assertThatThrownBy(() -> new ProductImageUploadFilter(true, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductImageUploadFilter(true, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
