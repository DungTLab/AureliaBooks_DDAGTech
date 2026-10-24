package com.ddagtech.aureliabooks.daemon;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.session.HttpSessionCreatedEvent;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * CRON-03: Session Registry Cleanup Daemon (FND-04).
 *
 * Periodically scans the Spring Security {@link SessionRegistry} and purges expired
 * {@link SessionInformation} records without undermining forced logout in
 * {@link org.springframework.security.web.session.ConcurrentSessionFilter}.
 *
 * Security Invariant:
 * {@link SessionInformation#expireNow()} only marks a session as expired so that
 * {@code ConcurrentSessionFilter} can invalidate the underlying {@link HttpSession} on the next request.
 * Removing the {@link SessionInformation} record while the underlying {@link HttpSession} is still active
 * would cause {@code ConcurrentSessionFilter} to miss the expiration marker and allow the stale session through.
 * Therefore, an expired {@link SessionInformation} record is removed ONLY if:
 * 1. Its tracked {@link HttpSession} is explicitly invalidated first, OR
 * 2. Its {@link HttpSession} has already been destroyed, OR
 * 3. Its {@code lastRequest} timestamp has exceeded the HTTP session idle timeout retention window.
 */
@Slf4j
@Component
public class SessionRegistryCleanupDaemon implements HttpSessionListener {

    /**
     * Default retention window for untracked expired session markers (30 minutes),
     * matching the standard servlet container HTTP session idle timeout.
     */
    public static final long DEFAULT_EXPIRED_SESSION_RETENTION_MS = 30L * 60L * 1000L;

    /**
     * Maximum capacity safety ceiling for tracking destroyed session IDs between scheduled passes,
     * protecting against burst session generation or DoS attacks.
     */
    public static final int MAX_DESTROYED_SESSION_TRACKING = 10_000;

    private final SessionRegistry sessionRegistry;
    private final ConcurrentMap<String, HttpSession> activeHttpSessions = new ConcurrentHashMap<>();
    private final Set<String> destroyedSessionIds = ConcurrentHashMap.newKeySet();

    private long expiredSessionRetentionMs = DEFAULT_EXPIRED_SESSION_RETENTION_MS;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public SessionRegistryCleanupDaemon(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Value("${app.cron.session-expired-retention-ms:1800000}")
    public void setExpiredSessionRetentionMs(long expiredSessionRetentionMs) {
        this.expiredSessionRetentionMs = Math.max(0L, expiredSessionRetentionMs);
    }

    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        if (event != null && event.getSession() != null) {
            registerHttpSession(event.getSession());
        }
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        if (event != null && event.getSession() != null) {
            markHttpSessionDestroyed(event.getSession().getId());
        }
    }

    @EventListener
    public void onHttpSessionCreated(HttpSessionCreatedEvent event) {
        if (event != null && event.getSession() != null) {
            registerHttpSession(event.getSession());
        }
    }

    @EventListener
    public void onHttpSessionDestroyed(HttpSessionDestroyedEvent event) {
        if (event != null && event.getId() != null) {
            markHttpSessionDestroyed(event.getId());
        }
    }

    /**
     * Tracks an active {@link HttpSession} so that forced-expired sessions can be invalidated
     * directly before their {@link SessionInformation} entry is purged.
     *
     * @param session active HTTP session
     */
    public void registerHttpSession(HttpSession session) {
        if (session != null && session.getId() != null) {
            destroyedSessionIds.remove(session.getId());
            activeHttpSessions.put(session.getId(), session);
        }
    }

    /**
     * Records that an {@link HttpSession} has been destroyed by the servlet container.
     *
     * @param sessionId destroyed HTTP session ID
     */
    public void markHttpSessionDestroyed(String sessionId) {
        if (sessionId != null) {
            activeHttpSessions.remove(sessionId);
            if (destroyedSessionIds.size() < MAX_DESTROYED_SESSION_TRACKING) {
                destroyedSessionIds.add(sessionId);
            }
        }
    }

    /**
     * Purges expired sessions from the in-memory SessionRegistry every 30 minutes.
     * Ensures any tracked HTTP session is invalidated before removing its SessionInformation,
     * preserves unexpired HTTP session markers so ConcurrentSessionFilter can enforce logout,
     * and performs internal housekeeping to evict destroyed IDs and stale sessions without leaking memory.
     *
     * @return number of expired sessions removed during this cleanup pass
     */
    @Scheduled(cron = "${app.cron.session-cleanup:0 */30 * * * ?}")
    public int purgeExpiredSessions() {
        long nowMillis = clock.millis();
        int purgedCount = 0;

        // 1. Collect all session IDs currently registered in the SessionRegistry across all principals
        Set<String> trackedRegistrySessionIds = new HashSet<>();
        List<Object> principals = sessionRegistry.getAllPrincipals();
        if (principals != null) {
            for (Object principal : principals) {
                if (principal == null) {
                    continue;
                }
                List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, true);
                if (sessions == null || sessions.isEmpty()) {
                    continue;
                }
                for (SessionInformation sessionInfo : sessions) {
                    if (sessionInfo != null && sessionInfo.getSessionId() != null) {
                        trackedRegistrySessionIds.add(sessionInfo.getSessionId());
                    }
                }
            }
        }

        // 2. Unconditional housekeeping: prune destroyed IDs that do not belong to any tracked registry session
        // (reclaims guest CSRF sessions, normal logouts, and sessions already removed by Spring Security)
        destroyedSessionIds.removeIf(id -> !trackedRegistrySessionIds.contains(id));

        // Evict any dead HttpSessions that were invalidated outside our lifecycle listener
        activeHttpSessions.entrySet().removeIf(entry -> {
            try {
                entry.getValue().getCreationTime();
                return false;
            } catch (IllegalStateException e) {
                return true;
            }
        });

        // If no principals exist in the SessionRegistry, internal housekeeping is complete
        if (principals == null || principals.isEmpty()) {
            return 0;
        }

        // 3. Process expired sessions for registered principals
        for (Object principal : principals) {
            if (principal == null) {
                continue;
            }
            List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, true);
            if (sessions == null || sessions.isEmpty()) {
                continue;
            }
            for (SessionInformation sessionInfo : sessions) {
                if (sessionInfo == null || !sessionInfo.isExpired()) {
                    continue;
                }
                String sessionId = sessionInfo.getSessionId();
                if (sessionId == null) {
                    continue;
                }
                if (canSafelyRemoveExpiredSession(sessionInfo, nowMillis)) {
                    sessionRegistry.removeSessionInformation(sessionId);
                    destroyedSessionIds.remove(sessionId);
                    purgedCount++;
                }
            }
        }

        if (purgedCount > 0) {
            log.info("CRON-03: Purged {} expired session(s) from Spring Security SessionRegistry.", purgedCount);
        }
        return purgedCount;
    }

    private boolean canSafelyRemoveExpiredSession(SessionInformation sessionInfo, long nowMillis) {
        String sessionId = sessionInfo.getSessionId();

        // 1. If the underlying HttpSession is tracked, invalidate it first before removing the registry entry
        HttpSession trackedSession = activeHttpSessions.remove(sessionId);
        if (trackedSession != null) {
            try {
                trackedSession.invalidate();
            } catch (IllegalStateException ignored) {
                // Session was already invalidated
            }
            return true;
        }

        // 2. If the container already reported the HttpSession as destroyed, the registry entry is safe to remove
        if (destroyedSessionIds.contains(sessionId)) {
            return true;
        }

        // 3. If the session is untracked, only remove it after its lastRequest exceeds the HTTP session idle timeout
        // so ConcurrentSessionFilter still sees the expired marker for any live HTTP session.
        Date lastRequest = sessionInfo.getLastRequest();
        if (lastRequest == null) {
            return true;
        }
        long idleDurationMs = nowMillis - lastRequest.getTime();
        return idleDurationMs >= expiredSessionRetentionMs;
    }

    /**
     * Returns the current number of tracked destroyed session IDs.
     * Useful for health metrics and verification testing.
     *
     * @return count of tracked destroyed session IDs
     */
    public int getDestroyedSessionCount() {
        return destroyedSessionIds.size();
    }

    /**
     * Returns the current number of tracked active HTTP sessions.
     * Useful for health metrics and verification testing.
     *
     * @return count of tracked active HTTP sessions
     */
    public int getActiveHttpSessionCount() {
        return activeHttpSessions.size();
    }
}
