package com.ddagtech.aureliabooks.daemon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CRON-03: Session Registry Cleanup Daemon (FND-04).
 *
 * Periodically scans the Spring Security {@link SessionRegistry} and purges expired
 * {@link SessionInformation} records so that forced-logout tracking for deactivated or
 * role-updated accounts (UC28 / BR-08-01) does not leak memory in the JVM heap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRegistryCleanupDaemon {

    private final SessionRegistry sessionRegistry;

    /**
     * Purges expired sessions from the in-memory SessionRegistry every 30 minutes.
     *
     * @return number of expired sessions removed during this cleanup pass
     */
    @Scheduled(cron = "${app.cron.session-cleanup:0 */30 * * * ?}")
    public int purgeExpiredSessions() {
        List<Object> principals = sessionRegistry.getAllPrincipals();
        if (principals == null || principals.isEmpty()) {
            return 0;
        }

        int purgedCount = 0;
        for (Object principal : principals) {
            if (principal == null) {
                continue;
            }
            List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, true);
            if (sessions == null || sessions.isEmpty()) {
                continue;
            }
            for (SessionInformation sessionInfo : sessions) {
                if (sessionInfo != null && sessionInfo.isExpired()) {
                    sessionRegistry.removeSessionInformation(sessionInfo.getSessionId());
                    purgedCount++;
                }
            }
        }

        if (purgedCount > 0) {
            log.info("CRON-03: Purged {} expired session(s) from Spring Security SessionRegistry.", purgedCount);
        }
        return purgedCount;
    }
}
