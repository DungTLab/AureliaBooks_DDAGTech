package com.ddagtech.aureliabooks.security;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptServiceTest {
    private final MutableClock clock = new MutableClock();
    private final LoginAttemptService attempts = new LoginAttemptService(clock);

    @Test void fifthFailureLocksAndCorrectCredentialsCannotBypassIt() {
        for (int i = 0; i < 4; i++) wrong(1L);
        assertThrows(TemporaryLoginLockException.class, () -> attempts.authenticate(1L, this::badPassword));
        assertThrows(TemporaryLoginLockException.class, () -> attempts.authenticate(1L, () -> "ok"));
        assertEquals("ok", attempts.authenticate(2L, () -> "ok"));
    }

    @Test void failuresAtTheTenMinuteBoundaryFallOutsideTheWindow() {
        for (int i = 0; i < 4; i++) wrong(1L);
        clock.advance(Duration.ofMinutes(10));
        wrong(1L);
        assertEquals("ok", attempts.authenticate(1L, () -> "ok"));
    }

    @Test void failuresJustInsideWindowLockButExpireAtExactlyFifteenMinutes() {
        for (int i = 0; i < 4; i++) wrong(1L);
        clock.advance(Duration.ofMinutes(10).minusMillis(1));
        assertThrows(TemporaryLoginLockException.class, () -> attempts.authenticate(1L, this::badPassword));
        clock.advance(Duration.ofMinutes(15).minusMillis(1));
        assertThrows(TemporaryLoginLockException.class, () -> attempts.authenticate(1L, () -> "ok"));
        clock.advance(Duration.ofMillis(1));
        assertEquals("ok", attempts.authenticate(1L, () -> "ok"));
        wrong(1L);
    }

    @Test void successfulAuthenticationResetsConsecutiveFailures() {
        for (int i = 0; i < 4; i++) wrong(1L);
        assertEquals("google", attempts.authenticate(1L, () -> "google"));
        for (int i = 0; i < 4; i++) wrong(1L);
        assertEquals("local", attempts.authenticate(1L, () -> "local"));
    }

    @Test void concurrentAttemptsCannotRacePastTheFifthFailure() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var passwordChecks = new AtomicInteger();
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> {
                start.await();
                try {
                    attempts.authenticate(1L, () -> { passwordChecks.incrementAndGet(); return badPassword(); });
                    fail("Wrong credentials must fail");
                } catch (BadCredentialsException | TemporaryLoginLockException expected) { }
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(5, passwordChecks.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private String badPassword() { throw new BadCredentialsException("wrong"); }
    private void wrong(Long id) { assertThrows(BadCredentialsException.class, () -> attempts.authenticate(id, this::badPassword)); }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
