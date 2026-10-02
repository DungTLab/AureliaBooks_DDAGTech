package com.ddagtech.aureliabooks.security;

import org.springframework.security.web.AuthenticationEntryPoint;

/** FND-04: TODO distinguish page redirect from API 401. Owner: Trần Huỳnh Giác. Sprint 1 scaffold; business implementation pending. */
public abstract class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {
    // FND-04: TODO distinguish page redirect from API 401
    // Developer must implement and explicitly register this extension point.
}
