package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.entity.Role;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Developer helper controller for simulating authenticated roles during development
 * while login functionality is under construction.
 */
@Slf4j
@Controller
@RequestMapping("/dev")
@RequiredArgsConstructor
public class DevAuthController {

    private final ObjectProvider<UserRepository> userRepositoryProvider;
    private final ObjectProvider<RoleRepository> roleRepositoryProvider;

    /**
     * Instantly grants ROLE_ADMIN and ROLE_MANAGER permissions to the active HTTP session,
     * allowing developers to test back-office and stock ledger functionality immediately.
     */
    @GetMapping("/login-admin")
    public String loginAsAdmin(
            @RequestParam(required = false, defaultValue = "/manager/stock/ledger") String redirect,
            HttpServletRequest request) {
        log.info("DevAuth: Granting ROLE_ADMIN & ROLE_MANAGER session, redirecting to {}", redirect);

        Role adminRole = Role.builder()
                .id(1L)
                .roleName("ROLE_ADMIN")
                .description("System Administrator")
                .build();

        User devUser = User.builder()
                .id(1L)
                .email("admin@aureliabook.vn")
                .fullName("Quản Trị Viên (Dev Test)")
                .phone("0988888888")
                .role(adminRole)
                .isActive(true)
                .gender(User.Gender.MALE)
                .authProvider(User.AuthProvider.LOCAL)
                .build();

        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_MANAGER"),
                new SimpleGrantedAuthority("ROLE_SALE_STAFF")
        );

        CustomUserDetails principal = new CustomUserDetails(devUser);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        HttpSession session = request.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        return "redirect:" + redirect;
    }

    /**
     * Instantly grants ROLE_MANAGER permissions to the active HTTP session.
     */
    @GetMapping("/login-manager")
    public String loginAsManager(
            @RequestParam(required = false, defaultValue = "/manager/stock/ledger") String redirect,
            HttpServletRequest request) {
        log.info("DevAuth: Granting ROLE_MANAGER session, redirecting to {}", redirect);

        Role managerRole = Role.builder()
                .id(2L)
                .roleName("ROLE_MANAGER")
                .description("Store Manager")
                .build();

        User devUser = User.builder()
                .id(2L)
                .email("manager@aureliabook.vn")
                .fullName("Quản Lý Kho (Dev Test)")
                .phone("0977777777")
                .role(managerRole)
                .isActive(true)
                .gender(User.Gender.MALE)
                .authProvider(User.AuthProvider.LOCAL)
                .build();

        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_MANAGER"),
                new SimpleGrantedAuthority("ROLE_SALE_STAFF")
        );

        CustomUserDetails principal = new CustomUserDetails(devUser);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        HttpSession session = request.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        return "redirect:" + redirect;
    }

    /**
     * Clears current development authentication session.
     */
    @GetMapping("/logout")
    public String devLogout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/auth/login";
    }
}
