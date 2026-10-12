package com.ddagtech.aureliabooks.security;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Single-instance NFR-09 store. No users schema change; state does not survive restart. */
@Service
public class LoginAttemptService {
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Duration LOCK = Duration.ofMinutes(15);
    private final Clock clock;
    private final ConcurrentHashMap<Long, Attempts> attempts = new ConcurrentHashMap<>();
    private final Object[] locks = new Object[128];
    private final AtomicLong lastCleanup = new AtomicLong();

    public LoginAttemptService() { this(Clock.systemUTC()); }

    LoginAttemptService(Clock clock) {
        this.clock = clock;
        for (int i=0;i<locks.length;i++) locks[i]=new Object();
    }

    /** Serialize check, authentication and update for the same account, including email/phone aliases. */
    public <T> T authenticate(Long userId, Supplier<T> authentication) {
        Objects.requireNonNull(userId, "Persistent account id is required");
        cleanup();
        synchronized (lockFor(userId)) {
            Instant now=clock.instant();
            Attempts state=attempts.get(userId);
            if (state != null && state.lockedUntil != null) {
                if (now.isBefore(state.lockedUntil)) throw new TemporaryLoginLockException();
                attempts.remove(userId);
            }
            try {
                T result=authentication.get();
                attempts.remove(userId); // successful local or trusted Google sign-in resets consecutive failures
                return result;
            } catch (BadCredentialsException failure) {
                now=clock.instant();
                state=attempts.computeIfAbsent(userId, ignored -> new Attempts());
                Instant cutoff=now.minus(WINDOW);
                while (!state.failures.isEmpty() && !state.failures.peekFirst().isAfter(cutoff)) {
                    state.failures.removeFirst();
                }
                state.failures.addLast(now);
                if (state.failures.size() >= 5) {
                    state.lockedUntil=now.plus(LOCK);
                    throw new TemporaryLoginLockException();
                }
                throw failure;
            }
        }
    }

    private Object lockFor(Long id) { return locks[Math.floorMod(id.hashCode(), locks.length)]; }

    private void cleanup() {
        long now=clock.millis(), previous=lastCleanup.get();
        if (now-previous < 60_000 || !lastCleanup.compareAndSet(previous,now)) return;
        attempts.forEach((id,ignored) -> {
            synchronized (lockFor(id)) {
                Attempts state=attempts.get(id);
                if (state == null) return;
                Instant instant=clock.instant();
                if (state.lockedUntil != null ? !instant.isBefore(state.lockedUntil)
                        : !state.failures.isEmpty() && !state.failures.peekLast().isAfter(instant.minus(WINDOW))) {
                    attempts.remove(id);
                }
            }
        });
    }

    private static class Attempts {
        private final Deque<Instant> failures=new ArrayDeque<>();
        private Instant lockedUntil;
    }
}
