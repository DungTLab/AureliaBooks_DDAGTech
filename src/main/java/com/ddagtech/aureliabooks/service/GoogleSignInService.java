package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.entity.User;

public interface GoogleSignInService {

    /** Only construct from provider claims validated by Spring Security. */
    record Identity(String subject, String email, boolean verified, String hostedDomain, String name, String picture) {
    }

    record Outcome(User user, String pendingFlow, boolean newlyLinked) {
    }

    Outcome resolve(Identity identity, String session, String ip);
}
