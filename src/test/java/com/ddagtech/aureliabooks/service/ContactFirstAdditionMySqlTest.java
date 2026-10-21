package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "contact.first.mysql", matches = "true")
class ContactFirstAdditionMySqlTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired SessionRegistry sessions;
    @MockitoBean ContactOtpDelivery delivery;
    String code;

    @BeforeEach void setup() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("google_link_qa_"));
        doAnswer(call -> { code = call.getArgument(2); return null; }).when(delivery).send(any(), any(), any());
    }

    void verifyEmail(boolean existingEmail) throws Exception {
        String marker = UUID.randomUUID().toString().replace("-", "");
        String phone = "09" + String.format("%08d", new java.security.SecureRandom().nextInt(100000000));
        String target = marker + "@example.com";
        jdbc.update("INSERT INTO users(email,phone,phone_verified,password_hash,full_name,role_id) VALUES(?,?,TRUE,?,'First Contact QA',(SELECT id FROM roles WHERE role_name='ROLE_CUSTOMER'))",
                existingEmail ? "old-" + target : null, phone, passwords.encode("QaPassword1!"));
        var owner = users.findByIdentifierWithRoles(phone).orElseThrow();
        var principal = new CustomUserDetails(owner);
        var session = new MockHttpSession();
        sessions.registerNewSession(session.getId(), principal);
        mvc.perform(post("/profile/contact-change/start").session(session).with(user(principal)).with(csrf())
                        .param("type", "EMAIL").param("value", target).param("password", "QaPassword1!"))
                .andExpect(redirectedUrl("/profile/contact-change/verification"));
        assertNotNull(code);
        var verification = mvc.perform(post("/profile/contact-change/verify").session(session).with(csrf()).param("code", code));
        if (existingEmail) {
            verification.andExpect(redirectedUrl("/auth/login")).andExpect(unauthenticated());
            assertTrue(session.isInvalid());
        } else {
            verification.andExpect(redirectedUrl("/profile")).andExpect(authenticated().withUsername(phone));
            assertFalse(session.isInvalid());
            assertFalse(sessions.getSessionInformation(session.getId()).isExpired());
            mvc.perform(get("/profile").session(session)).andExpect(status().isOk()).andExpect(authenticated());
        }
        assertEquals(target, jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, owner.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='PROFILE_EMAIL_CHANGED' AND target_id=?", Integer.class, owner.getId()));
    }

    @Test void firstEmailAdditionPreservesAuthenticatedProfileAccess() throws Exception { verifyEmail(false); }
    @Test void replacingExistingEmailStillRequiresLogin() throws Exception { verifyEmail(true); }
}
