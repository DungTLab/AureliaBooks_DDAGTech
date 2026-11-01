package com.ddagtech.aureliabooks.config;

import com.ddagtech.aureliabooks.security.AuthEntryGuard;
import com.ddagtech.aureliabooks.security.PortalAuthenticationProvider;
import com.ddagtech.aureliabooks.security.PortalSecurity;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import com.ddagtech.aureliabooks.security.CustomAccessDeniedHandler;
import com.ddagtech.aureliabooks.security.CustomAuthenticationEntryPoint;
import com.ddagtech.aureliabooks.security.CustomAuthenticationFailureHandler;
import com.ddagtech.aureliabooks.security.CustomOAuth2UserService;
import com.ddagtech.aureliabooks.security.CustomUserDetailsService;
import com.ddagtech.aureliabooks.security.LoginAttemptService;
import com.ddagtech.aureliabooks.security.LogoutAuditHandler;
import com.ddagtech.aureliabooks.security.RoleBasedAuthenticationSuccessHandler;
import com.ddagtech.aureliabooks.security.ThrottledAuthenticationProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;

/**
 * Foundational Spring Security 6 Configuration (FND-04).
 * Enforces 4 Lean RBAC roles across Storefront and Back-Office zones,
 * manages session fixation protection, concurrent session control,
 * and role-tailored redirect workflows.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ObjectProvider<CustomUserDetailsService> userDetailsServiceProvider;
    private final ObjectProvider<RoleBasedAuthenticationSuccessHandler> authenticationSuccessHandlerProvider;
    private final ObjectProvider<CustomAuthenticationFailureHandler> authenticationFailureHandlerProvider;
    private final ObjectProvider<CustomAccessDeniedHandler> accessDeniedHandlerProvider;
    private final ObjectProvider<CustomAuthenticationEntryPoint> authenticationEntryPointProvider;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;
    private final ObjectProvider<CustomOAuth2UserService> oauthUsers;
    private final ObjectProvider<LoginAttemptService> loginAttempts;
    private final ObjectProvider<LogoutAuditHandler> logoutAudit;

    /**
     * Password encoder utilizing BCrypt hashing with work factor (cost) of 12.
     * Complies with industrial credential storage standards and BR-07-03.
     *
     * @return BCryptPasswordEncoder instance
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * In-memory session registry for tracking concurrent authenticated sessions
     * and supporting administrative session invalidation upon status revocation (BR-07-04 / UC28).
     *
     * @return SessionRegistry instance
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Publishes HTTP session creation and destruction events to the Spring ApplicationContext.
     * Required for synchronizing the {@link SessionRegistry}.
     *
     * @return HttpSessionEventPublisher instance
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * Google callbacks run in Security filters before MVC; expose their session-bound intent there.
     */
    @Bean
    public org.springframework.web.context.request.RequestContextListener oauthRequestContextListener() {
        return new org.springframework.web.context.request.RequestContextListener();
    }

    /**
     * Exposes the AuthenticationManager bean for programmatic authentication flows.
     *
     * @param config authentication configuration
     * @return AuthenticationManager instance
     * @throws Exception if configuration retrieval fails
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /** Higher-priority chain for management pages and administrative APIs. */
    @Bean
    @Order(1)
    public SecurityFilterChain internalSecurityFilterChain(HttpSecurity http,
            @org.springframework.beans.factory.annotation.Value("${app.r2.upload-enabled:true}") boolean uploadsEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.r2.upload-requests-per-minute:10}") int requestsPerMinute,
            @org.springframework.beans.factory.annotation.Value("${app.r2.upload-max-concurrent:2}") int maxConcurrent) throws Exception {
        http.securityMatcher(PortalSecurity::internalRequest);
        // Construct only inside the security chain; not a servlet Filter bean (no double registration).
        http.addFilterBefore(new com.ddagtech.aureliabooks.security.ProductImageUploadFilter(
                uploadsEnabled, requestsPerMinute, maxConcurrent),
                org.springframework.security.web.csrf.CsrfFilter.class);
        portalAuthentication(http, true);
        http.addFilterBefore(new AuthEntryGuard(),
                UsernamePasswordAuthenticationFilter.class);
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/internal/login").permitAll()
                .requestMatchers("/admin/users", "/admin/users/**", "/admin/audit-logs", "/admin/audit-logs/**").hasRole("ADMIN")
                .requestMatchers("/admin/analytics/**", "/manager/analytics/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers("/manager/stock/history", "/manager/inventory/history").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                .requestMatchers("/manager/stock/alerts", "/staff/stock/alerts", "/inventory/stock/alerts").hasAnyRole("MANAGER", "SALE_STAFF")
                .requestMatchers("/manager/receipts/*/finalize").hasRole("MANAGER")
                .requestMatchers(HttpMethod.GET, "/api/receipts/**").hasAnyRole("ADMIN", "MANAGER", "SALE_STAFF")
                .requestMatchers(
                        "/manager/receipts",
                        "/manager/receipts/**",
                        "/staff/receipts",
                        "/staff/receipts/**",
                        "/api/receipts/**",
                        "/api/products/quick-create"
                ).hasAnyRole("MANAGER", "SALE_STAFF")
                .requestMatchers("/manager", "/manager/**").hasRole("MANAGER")
                .requestMatchers("/staff", "/staff/**").hasAnyRole("SALE_STAFF", "MANAGER")
                .requestMatchers("/dashboard", "/dashboard/**", "/inventory", "/inventory/**").hasAnyRole("ADMIN", "MANAGER", "SALE_STAFF")
                .anyRequest().hasAnyRole("ADMIN", "MANAGER", "SALE_STAFF"));
        http.formLogin(login -> {
            login.loginPage("/internal/login").loginProcessingUrl("/internal/login").permitAll();
            var success = authenticationSuccessHandlerProvider.getIfAvailable();
            if (success != null) login.successHandler(success);
            else login.defaultSuccessUrl("/dashboard", true);
            var failure = authenticationFailureHandlerProvider.getIfAvailable();
            if (failure != null) login.failureHandler(failure);
            else login.failureUrl("/internal/login?error=true");
        });
        http.logout(logout -> {
            logout.logoutUrl("/internal/logout").logoutSuccessUrl("/internal/login?logout")
                    .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID");
            var audit = logoutAudit.getIfAvailable();
            if (audit != null) logout.addLogoutHandler(audit);
        });
        http.sessionManagement(session -> session.sessionFixation(fixation -> fixation.migrateSession())
                .maximumSessions(5).sessionRegistry(sessionRegistry()));
        http.exceptionHandling(exceptions -> {
            var denied = accessDeniedHandlerProvider.getIfAvailable();
            if (denied != null) exceptions.accessDeniedHandler(denied);
            var entry = authenticationEntryPointProvider.getIfAvailable();
            if (entry != null) exceptions.authenticationEntryPoint(entry);
            else exceptions.authenticationEntryPoint(
                    new LoginUrlAuthenticationEntryPoint("/internal/login"));
        });
        http.headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin));
        return http.build();
    }

    private void portalAuthentication(HttpSecurity http, boolean internal) {
        var users = userDetailsServiceProvider.getIfAvailable();
        if (users == null) return;
        var throttled = new ThrottledAuthenticationProvider(users, passwordEncoder(),
                loginAttempts.getIfAvailable(LoginAttemptService::new));
        // Keep OAuth providers in this chain, but disallow an unrestricted parent fallback.
        http.getSharedObject(AuthenticationManagerBuilder.class)
                .parentAuthenticationManager(null);
        http.authenticationProvider(new PortalAuthenticationProvider(throttled, internal));
    }

    /**
     * Storefront filter chain configuring customer authorization rules,
     * session security, login handling, and logout mechanics.
     *
     * @param http HttpSecurity builder
     * @return built SecurityFilterChain
     * @throws Exception if security DSL building fails
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        portalAuthentication(http, false);
        http.addFilterBefore(new AuthEntryGuard(),
                OAuth2AuthorizationRequestRedirectFilter.class);

        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/payments/vnpay-ipn"))
            .authorizeHttpRequests(authorize ->
                authorize
                    // 1. Public Storefront Catalog, Auth Entry, VNPay IPN Webhook, and Static Assets
                    .requestMatchers(
                        "/",
                        "/home",
                        "/products/**",
                        "/policies/**",
                        "/auth/**",
                        "/api/v1/payments/vnpay-ipn",
                        "/css/**",
                        "/js/**",
                        "/images/**",
                        "/webjars/**",
                        "/favicon.ico",
                        "/error/**"
                    )
                    .permitAll()

                    // 2. Storefront Member Operations (Strictly No Guest Cart: BR-03-01, BR-04-04, F04)
                    .requestMatchers(
                        "/cart",
                        "/cart/**",
                        "/checkout",
                        "/checkout/**",
                        "/payment",
                        "/payment/**",
                        "/account",
                        "/account/**",
                        "/orders",
                        "/orders/**",
                        "/profile",
                        "/profile/**"
                    )
                    .hasRole("CUSTOMER")

                    // 3. Default: All other routes require an authenticated session
                    .anyRequest()
                    .authenticated()
            )
            .formLogin(login -> {
                login
                    .loginPage("/auth/login")
                    .loginProcessingUrl("/auth/login")
                    .usernameParameter("username")
                    .passwordParameter("password")
                    .permitAll();

                RoleBasedAuthenticationSuccessHandler successHandler =
                    authenticationSuccessHandlerProvider.getIfAvailable();
                if (successHandler != null) {
                    login.successHandler(successHandler);
                } else {
                    login.defaultSuccessUrl("/", false);
                }

                CustomAuthenticationFailureHandler failureHandler =
                    authenticationFailureHandlerProvider.getIfAvailable();
                if (failureHandler != null) {
                    login.failureHandler(failureHandler);
                } else {
                    login.failureUrl("/auth/login?error=true");
                }
            })
            .logout(logout -> {
                logout
                    .logoutUrl("/auth/logout")
                    .logoutSuccessUrl("/")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("JSESSIONID")
                    .permitAll();
                var audit = logoutAudit.getIfAvailable();
                if (audit != null) logout.addLogoutHandler(audit);
            })
            .sessionManagement(session ->
                session
                    .sessionFixation(fixation -> fixation.migrateSession())
                    .maximumSessions(5)
                    .sessionRegistry(sessionRegistry())
            );

        CustomAccessDeniedHandler accessDeniedHandler = accessDeniedHandlerProvider.getIfAvailable();
        CustomAuthenticationEntryPoint authenticationEntryPoint = authenticationEntryPointProvider.getIfAvailable();
        if (accessDeniedHandler != null || authenticationEntryPoint != null) {
            http.exceptionHandling(exceptions -> {
                if (accessDeniedHandler != null) {
                    exceptions.accessDeniedHandler(accessDeniedHandler);
                }
                if (authenticationEntryPoint != null) {
                    exceptions.authenticationEntryPoint(authenticationEntryPoint);
                }
            });
        }

        http.headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin));

        // Enable Google only when the opt-in google profile supplies a client registration.
        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> {
                oauth
                    .loginPage("/auth/login")
                    .authorizationEndpoint(endpoint ->
                        endpoint.authorizationRequestResolver(
                            new com.ddagtech.aureliabooks.security.GoogleAuthorizationRequestResolver(
                                clientRegistrations.getObject()
                            )
                        )
                    )
                    .userInfoEndpoint(info ->
                        info.userService(oauthUsers.getObject()).oidcUserService(oauthUsers.getObject()::loadOidcUser)
                    )
                    .failureHandler((request, response, exception) -> {
                        if (
                            exception instanceof OAuth2AuthenticationException pending &&
                            "google_link_required".equals(pending.getError().getErrorCode()) &&
                            request.getSession(false) != null &&
                            request.getSession(false).getAttribute("GOOGLE_LINK_FLOW") != null
                        ) {
                            response.sendRedirect(request.getContextPath() + "/auth/google-link");
                            return;
                        }
                        String message = "Không thể xác thực bằng Google. Vui lòng thử lại.";
                        if (
                            exception instanceof OAuth2AuthenticationException failure &&
                            "registration_failed".equals(failure.getError().getErrorCode())
                        ) {
                            message = failure.getMessage();
                        }
                        if (
                            com.ddagtech.aureliabooks.security.GoogleAccountAction.restoreAfterFailure(
                                request,
                                response
                            )
                        ) {
                            FlashMap reauthFlash = new FlashMap();
                            reauthFlash.put("contactMessage", message);
                            String target = com.ddagtech.aureliabooks.security.GoogleAccountAction.returnPath(
                                request.getSession()
                            );
                            reauthFlash.setTargetRequestPath(request.getContextPath() + "/profile/contact-change");
                            new SessionFlashMapManager().saveOutputFlashMap(reauthFlash, request, response);
                            response.sendRedirect(request.getContextPath() + target);
                            return;
                        }
                        // OAuth filters execute before DispatcherServlet creates its output flash map.
                        FlashMap flash = new FlashMap();
                        flash.put("errorMessage", message);
                        Object origin =
                            request.getSession(false) == null
                                ? null
                                : request.getSession(false).getAttribute("GOOGLE_AUTH_ORIGIN");
                        String destination = "login".equals(origin) ? "/auth/login" : "/auth/register";
                        if (request.getSession(false) != null) request
                            .getSession(false)
                            .removeAttribute("GOOGLE_AUTH_ORIGIN");
                        flash.setTargetRequestPath(request.getContextPath() + destination);
                        new SessionFlashMapManager().saveOutputFlashMap(flash, request, response);
                        response.sendRedirect(request.getContextPath() + destination);
                    });
                var success = authenticationSuccessHandlerProvider.getIfAvailable();
                if (success != null) oauth.successHandler(success);
                else oauth.defaultSuccessUrl("/", true);
            });
        }

        return http.build();
    }
}
