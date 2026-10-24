package com.ddagtech.aureliabooks.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.controller.admin.AuditLogController;
import com.ddagtech.aureliabooks.dto.response.AuditLogResponse;
import com.ddagtech.aureliabooks.entity.AuditLog;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.AuditLogRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.ddagtech.aureliabooks.service.impl.AuditLogServiceImpl;
import com.ddagtech.aureliabooks.util.HttpRequestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Comprehensive Security and Robustness Verification Test Suite for UC29 (SWP-56 / TASK-GIAC-05).
 * Validates Lean RBAC access boundaries, DoS input bounds, CWE-290 anti-IP spoofing,
 * credential sanitization depth, and [BR-08-02] immutability invariants.
 */
class AuditLogSecurityTest {

    @Nested
    @DisplayName("1. RBAC & Route Access Control Verification")
    @WebMvcTest(controllers = AuditLogController.class)
    @Import({
        SecurityConfig.class,
        CustomAccessDeniedHandler.class,
        CustomAuthenticationEntryPoint.class,
        RoleBasedAuthenticationSuccessHandler.class,
        CustomAuthenticationFailureHandler.class,
    })
    static class RbacAccessControlTests {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private AuditLogService auditLogService;

        @MockitoBean
        private CustomUserDetailsService userDetailsService;

        @Test
        @DisplayName("SEC-01: Anonymous request to /admin/audit-logs must redirect to /auth/login")
        void testAnonymousAccess_RedirectsToLogin() throws Exception {
            mockMvc
                .perform(get("/admin/audit-logs"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/login"));
        }

        @Test
        @DisplayName("SEC-02: Authenticated CUSTOMER accessing /admin/audit-logs must receive 403 Forbidden redirect")
        void testCustomerAccess_ReturnsForbidden() throws Exception {
            mockMvc
                .perform(get("/admin/audit-logs").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/403"));
        }

        @Test
        @DisplayName("SEC-03: Authenticated SALE_STAFF accessing /admin/audit-logs must receive 403 Forbidden redirect")
        void testSaleStaffAccess_ReturnsForbidden() throws Exception {
            mockMvc
                .perform(get("/admin/audit-logs").with(user("staff").roles("SALE_STAFF")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/403"));
        }

        @Test
        @DisplayName("SEC-04: Authenticated MANAGER accessing /admin/audit-logs must receive 403 Forbidden redirect")
        void testManagerAccess_ReturnsForbidden() throws Exception {
            mockMvc
                .perform(get("/admin/audit-logs").with(user("manager").roles("MANAGER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/error/403"));
        }

        @Test
        @DisplayName("SEC-05: Authenticated ADMIN accessing /admin/audit-logs must return 200 OK")
        void testAdminAccess_Returns200Ok() throws Exception {
            when(auditLogService.searchLogs(any(), any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());
            when(auditLogService.getDistinctActions()).thenReturn(List.of());
            when(auditLogService.getDistinctTargetTables()).thenReturn(List.of());
            when(auditLogService.getDistinctActors()).thenReturn(List.of());

            mockMvc
                .perform(get("/admin/audit-logs").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/audit_logs"));
        }

        @Test
        @DisplayName("SEC-06: Unauthenticated AJAX request receives HTTP 401 JSON ApiResponse")
        void testAjaxUnauthenticated_ReturnsJson401() throws Exception {
            mockMvc
                .perform(get("/admin/audit-logs").header("Accept", "application/json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));
        }

        @Test
        @DisplayName("SEC-07: Authenticated CUSTOMER AJAX request receives HTTP 403 JSON ApiResponse")
        void testAjaxCustomer_ReturnsJson403() throws Exception {
            mockMvc
                .perform(
                    get("/admin/audit-logs")
                        .with(user("customer").roles("CUSTOMER"))
                        .header("Accept", "application/json")
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1002));
        }

        @Test
        @DisplayName("SEC-08: DoS Pagination bounds check: negative page is clamped to 0, size > 100 is clamped to 20")
        void testPaginationBoundsClamping_PreventsMemoryExhaustion() throws Exception {
            when(auditLogService.searchLogs(any(), any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());
            when(auditLogService.getDistinctActions()).thenReturn(List.of());
            when(auditLogService.getDistinctTargetTables()).thenReturn(List.of());
            when(auditLogService.getDistinctActors()).thenReturn(List.of());

            mockMvc
                .perform(
                    get("/admin/audit-logs")
                        .with(user("admin").roles("ADMIN"))
                        .param("page", "-50")
                        .param("size", "999999")
                )
                .andExpect(status().isOk());

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(auditLogService).searchLogs(
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                pageableCaptor.capture()
            );

            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(0);
            assertThat(captured.getPageSize()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("2. Credential Redaction & Information Leakage Prevention")
    static class CredentialSanitizationTests {

        private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        private final UserRepository userRepository = mock(UserRepository.class);
        private final AuditLogServiceImpl auditLogService = new AuditLogServiceImpl(
            auditLogRepository,
            userRepository,
            new ObjectMapper()
        );

        @Test
        @DisplayName("SEC-09: Redacts nested credentials across objects, arrays, and mixed-case field names")
        void testDeepNestedCredentials_RedactedSuccessfully() {
            Long actorId = 1L;
            User actor = User.builder().id(actorId).email("admin@aureliabook.vn").build();
            when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));

            String complexPayload = """
            {
                "user": {
                    "name": "Target User",
                    "password": "MySuperSecretPassword@2026",
                    "AUTH_TOKEN": "Bearer xyz-999-secret",
                    "otp_code": "654321"
                },
                "billing": {
                    "card_cvv": "123",
                    "api_secret_key": "sec_live_987654"
                },
                "safeField": "public_value"
            }
            """;

            auditLogService.record(actorId, "USER_UPDATE", "users", 10L, complexPayload, "127.0.0.1");

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());

            String savedJson = captor.getValue().getDetailsJson();
            assertThat(savedJson).contains("\"password\":\"[REDACTED]\"");
            assertThat(savedJson).contains("\"AUTH_TOKEN\":\"[REDACTED]\"");
            assertThat(savedJson).contains("\"otp_code\":\"[REDACTED]\"");
            assertThat(savedJson).contains("\"card_cvv\":\"[REDACTED]\"");
            assertThat(savedJson).contains("\"api_secret_key\":\"[REDACTED]\"");
            assertThat(savedJson).contains("\"safeField\":\"public_value\"");
            assertThat(savedJson).doesNotContain("MySuperSecretPassword@2026");
            assertThat(savedJson).doesNotContain("Bearer xyz-999-secret");
            assertThat(savedJson).doesNotContain("654321");
            assertThat(savedJson).doesNotContain("123");
            assertThat(savedJson).doesNotContain("sec_live_987654");
        }

        @Test
        @DisplayName("SEC-10: Fallback regex masks credentials in malformed/unparseable JSON")
        void testMalformedJson_FallbackRegexRedaction() {
            String malformedPayload =
                "malformed_json_block: {\"password\": \"leakMe123\", \"access_token\": \"leakToken456\"}";

            auditLogService.record(null, "MALFORMED_ACTION", "raw", null, malformedPayload, "127.0.0.1");

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());

            String saved = captor.getValue().getDetailsJson();
            assertThat(saved).contains("[REDACTED]");
            assertThat(saved).doesNotContain("leakMe123");
            assertThat(saved).doesNotContain("leakToken456");
        }
    }

    @Nested
    @DisplayName("3. CWE-290 Anti-IP Spoofing & Audit Origin Defense")
    static class AntiIpSpoofingTests {

        @Test
        @DisplayName("SEC-11: Direct untrusted public socket IP ignores malicious X-Forwarded-For spoofing")
        void testUntrustedPublicIp_IgnoresSpoofedHeaders() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.195"); // Public untrusted IP
            request.addHeader("X-Forwarded-For", "8.8.8.8, 1.1.1.1");
            request.addHeader("X-Real-IP", "127.0.0.1");

            String resolvedIp = HttpRequestUtil.getClientIp(request);
            assertThat(resolvedIp).isEqualTo("203.0.113.195");
        }

        @Test
        @DisplayName("SEC-12: Trusted loopback reverse proxy (127.0.0.1) securely extracts validated client IP")
        void testTrustedLoopbackProxy_ExtractsForwardedIp() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("127.0.0.1"); // Trusted loopback
            request.addHeader("X-Forwarded-For", "118.69.182.42, 10.0.0.1");

            String resolvedIp = HttpRequestUtil.getClientIp(request);
            assertThat(resolvedIp).isEqualTo("118.69.182.42");
        }
    }

    @Nested
    @DisplayName("4. Immutability Invariant [BR-08-02] Verification")
    static class ImmutabilityInvariantTests {

        private final AuditLogRepository auditLogRepository = new AuditLogRepository() {
            @Override
            public AuditLog save(AuditLog auditLog) {
                return auditLog;
            }

            @Override
            public Optional<AuditLog> findById(Long id) {
                return Optional.empty();
            }

            @Override
            public Page<AuditLog> findAll(Pageable pageable) {
                return Page.empty();
            }

            @Override
            public Page<AuditLog> searchLogs(
                String a,
                String t,
                Long actorId,
                LocalDateTime s,
                LocalDateTime e,
                String k,
                Pageable p
            ) {
                return Page.empty();
            }

            @Override
            public List<String> findDistinctActions() {
                return List.of();
            }

            @Override
            public List<String> findDistinctTargetTables() {
                return List.of();
            }

            @Override
            public List<com.ddagtech.aureliabooks.dto.response.ActorOption> findDistinctActors() {
                return List.of();
            }
        };

        @Test
        @DisplayName("SEC-13: Repository rejects delete() mutation to preserve tamper-evident audit ledger")
        void testDeleteEntity_Rejected() {
            AuditLog log = new AuditLog();
            assertThatThrownBy(() -> auditLogRepository.delete(log))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("[BR-08-02]");
        }

        @Test
        @DisplayName("SEC-14: Repository rejects deleteById() mutation")
        void testDeleteById_Rejected() {
            assertThatThrownBy(() -> auditLogRepository.deleteById(999L))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("[BR-08-02]");
        }

        @Test
        @DisplayName("SEC-15: Repository rejects deleteAll() purge mutation")
        void testDeleteAll_Rejected() {
            assertThatThrownBy(auditLogRepository::deleteAll)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("[BR-08-02]");
        }
    }
}
