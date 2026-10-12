package com.ddagtech.aureliabooks.config;

import com.ddagtech.aureliabooks.security.CustomAccessDeniedHandler;
import com.ddagtech.aureliabooks.security.CustomAuthenticationEntryPoint;
import com.ddagtech.aureliabooks.security.CustomAuthenticationFailureHandler;
import com.ddagtech.aureliabooks.security.CustomUserDetailsService;
import com.ddagtech.aureliabooks.security.CustomOAuth2UserService;
import com.ddagtech.aureliabooks.security.LoginAttemptService;
import com.ddagtech.aureliabooks.security.ThrottledAuthenticationProvider;
import com.ddagtech.aureliabooks.security.LogoutAuditHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.support.SessionFlashMapManager;
import com.ddagtech.aureliabooks.security.RoleBasedAuthenticationSuccessHandler;
import org.springframework.beans.factory.ObjectProvider;
import lombok.RequiredArgsConstructor;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

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

    /**
     * Primary HTTP Security Filter Chain configuring Lean RBAC authorization rules,
     * session security, login handling, and logout mechanics.
     *
     * @param http HttpSecurity builder
     * @return built SecurityFilterChain
     * @throws Exception if security DSL building fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CustomUserDetailsService userDetailsService = userDetailsServiceProvider.getIfAvailable();
        if (userDetailsService != null) {
            var authProvider = new ThrottledAuthenticationProvider(userDetailsService, passwordEncoder(),
                    loginAttempts.getIfAvailable(LoginAttemptService::new));
            http.authenticationProvider(authProvider);
        }

        http
            .authorizeHttpRequests(authorize -> authorize
                // 1. Public Storefront Catalog, Auth Entry, and Static Assets
                .requestMatchers(
                    "/",
                    "/home",
                    "/products/**",
                    "/auth/**",
                    "/css/**",
                    "/js/**",
                    "/images/**",
                    "/webjars/**",
                    "/favicon.ico",
                    "/error/**"
                ).permitAll()

                // 2. Back-Office Administrative Routes (Lean RBAC Governance)
                .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                .requestMatchers("/manager", "/manager/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers("/staff", "/staff/**").hasAnyRole("ADMIN", "MANAGER", "SALE_STAFF")
                .requestMatchers("/inventory", "/inventory/**").hasAnyRole("ADMIN", "MANAGER", "SALE_STAFF")
                .requestMatchers("/dashboard", "/dashboard/**").hasAnyRole("ADMIN", "MANAGER", "SALE_STAFF")

                // 3. Storefront Member Operations (Strictly No Guest Cart: BR-03-01, BR-04-04)
                .requestMatchers(
                    "/cart", "/cart/**",
                    "/checkout", "/checkout/**",
                    "/account", "/account/**",
                    "/orders", "/orders/**",
                    "/profile", "/profile/**"
                ).hasRole("CUSTOMER")

                // 4. Default: All other routes require an authenticated session
                .anyRequest().authenticated()
            )
            .formLogin(login -> {
                login
                    .loginPage("/auth/login")
                    .loginProcessingUrl("/auth/login")
                    .usernameParameter("username")
                    .passwordParameter("password")
                    .permitAll();

                RoleBasedAuthenticationSuccessHandler successHandler = authenticationSuccessHandlerProvider.getIfAvailable();
                if (successHandler != null) {
                    login.successHandler(successHandler);
                } else {
                    login.defaultSuccessUrl("/", false);
                }

                CustomAuthenticationFailureHandler failureHandler = authenticationFailureHandlerProvider.getIfAvailable();
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
                var audit=logoutAudit.getIfAvailable();
                if (audit != null) logout.addLogoutHandler(audit);
            })
            .sessionManagement(session -> session
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

        http.headers(headers -> headers
            .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
        );

        // Enable Google only when the opt-in google profile supplies a client registration.
        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> {
                oauth.loginPage("/auth/login")
                        .userInfoEndpoint(info -> info.userService(oauthUsers.getObject())
                                .oidcUserService(oauthUsers.getObject()::loadOidcUser))
                        .failureHandler((request, response, exception) -> {
                            String message = "Không thể xác thực bằng Google. Vui lòng thử lại.";
                            if (exception instanceof OAuth2AuthenticationException failure
                                    && "registration_failed".equals(failure.getError().getErrorCode())) {
                                message = failure.getMessage();
                            }
                            // OAuth filters execute before DispatcherServlet creates its output flash map.
                            FlashMap flash = new FlashMap();
                            flash.put("errorMessage", message);
                            Object origin = request.getSession(false) == null ? null
                                    : request.getSession(false).getAttribute("GOOGLE_AUTH_ORIGIN");
                            String destination = "login".equals(origin) ? "/auth/login" : "/auth/register";
                            if (request.getSession(false) != null) request.getSession(false).removeAttribute("GOOGLE_AUTH_ORIGIN");
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

