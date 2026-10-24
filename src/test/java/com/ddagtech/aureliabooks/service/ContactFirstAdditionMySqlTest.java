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
    @Autowired GoogleSignInService googleSignIn;
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
        mvc.perform(post("/profile/contact-change/reauth").session(session).with(user(principal)).with(csrf())
                        .param("type", "EMAIL").param("password", "QaPassword1!"))
                .andExpect(redirectedUrl("/profile/contact-change?type=EMAIL"));
        mvc.perform(post("/profile/contact-change/start").session(session).with(user(principal)).with(csrf())
                        .param("type", "EMAIL").param("value", target).param("password", "QaPassword1!"))
                .andExpect(redirectedUrl("/profile/contact-change/verification"));
        assertNotNull(code);
        var verification = mvc.perform(post("/profile/contact-change/verify").session(session).with(csrf()).param("code", code));
        verification.andExpect(redirectedUrl("/profile")).andExpect(authenticated());
        assertFalse(session.isInvalid());
        assertFalse(sessions.getSessionInformation(session.getId()).isExpired());
        mvc.perform(get("/profile").session(session)).andExpect(status().isOk()).andExpect(authenticated());
        assertEquals(target, jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, owner.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='PROFILE_EMAIL_CHANGED' AND target_id=?", Integer.class, owner.getId()));
    }

    @Test void firstEmailAdditionPreservesAuthenticatedProfileAccess() throws Exception { verifyEmail(false); }

    @Test void googleCustomerCreatesFirstPasswordOnlyAfterOtpThenCanLoginLocallyAndWithGoogle() throws Exception {
        String marker = UUID.randomUUID().toString().replace("-", "");
        String email = marker + "@gmail.com", subject = "setup-" + marker;
        jdbc.update("INSERT INTO users(email,email_verified,full_name,auth_provider,provider_id,role_id) VALUES(?,TRUE,'Google Password QA','GOOGLE',?,(SELECT id FROM roles WHERE role_name='ROLE_CUSTOMER'))", email, subject);
        Long owner = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
        var session = new MockHttpSession();
        var oidc = oidcLogin().idToken(token -> token.subject(subject).claim("email", email).claim("email_verified", true))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"));
        mvc.perform(post("/profile/password/setup").session(session).with(oidc).with(csrf())
                .param("newPassword", "NewPass1!").param("confirmPassword", "NewPass1!"))
                .andExpect(redirectedUrl("/profile/password/setup"));
        assertNull(jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, owner));
        mvc.perform(post("/profile/password/setup/start").session(session).with(oidc).with(csrf()).param("channel", "EMAIL"))
                .andExpect(redirectedUrl("/profile/contact-change/verification"));
        String issued = code;
        assertNotNull(issued);
        mvc.perform(post("/profile/contact-change/verify").session(session).with(oidc).with(csrf()).param("code", issued.equals("000000") ? "111111" : "000000"))
                .andExpect(redirectedUrl("/profile/contact-change/verification"));
        mvc.perform(post("/profile/contact-change/verify").session(session).with(oidc).with(csrf()).param("code", issued))
                .andExpect(redirectedUrl("/profile/password/setup"));
        mvc.perform(post("/profile/password/setup").session(session).with(oidc).with(csrf())
                .param("newPassword", "weak").param("confirmPassword", "weak"))
                .andExpect(redirectedUrl("/profile/password/setup"));
        assertNull(jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, owner));
        mvc.perform(post("/profile/password/setup").session(session).with(oidc).with(csrf())
                .param("newPassword", "NewPass1!").param("confirmPassword", "NewPass1!"))
                .andExpect(redirectedUrl("/auth/login?passwordChanged")).andExpect(unauthenticated());
        assertTrue(passwords.matches("NewPass1!", jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, owner)));
        mvc.perform(post("/auth/login").with(csrf()).param("username", email).param("password", "NewPass1!"))
                .andExpect(authenticated());
        assertEquals(owner, googleSignIn.resolve(new GoogleSignInService.Identity(subject, email, true, null,
                "Google Password QA", null), "setup-new-login", "127.0.0.1").user().getId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE user_id=? AND action='PASSWORD_CREATED'", Integer.class, owner));
    }
    @Test void replacingExistingEmailKeepsCurrentSession() throws Exception { verifyEmail(true); }

    @Test void googleOnlyEmailChangeKeepsSessionDetachesOldSubjectAndLinksNewGoogleToSameAccount() throws Exception {
        String marker=UUID.randomUUID().toString().replace("-", "");
        String oldEmail="old-"+marker+"@gmail.com", newEmail="new-"+marker+"@gmail.com", oldSub="old-"+marker;
        jdbc.update("INSERT INTO users(email,email_verified,full_name,auth_provider,provider_id,role_id) VALUES(?,TRUE,'Google Contact QA','GOOGLE',?,(SELECT id FROM roles WHERE role_name='ROLE_CUSTOMER'))",oldEmail,oldSub);
        Long owner=jdbc.queryForObject("SELECT id FROM users WHERE email=?",Long.class,oldEmail);
        var token=new org.springframework.security.oauth2.core.oidc.OidcIdToken("qa-token",java.time.Instant.now(),java.time.Instant.now().plusSeconds(600),java.util.Map.of("sub",oldSub,"email",oldEmail,"email_verified",true));
        var roles=java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"));
        var google=new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(roles,token);
        var authentication=new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(google,roles,"google");
        var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();context.setAuthentication(authentication);
        var session=new MockHttpSession();session.setAttribute("SPRING_SECURITY_CONTEXT",context);sessions.registerNewSession(session.getId(),google);
        mvc.perform(post("/profile/contact-change/reauth").session(session).with(csrf()).param("type","EMAIL").param("method","EMAIL"))
                .andExpect(redirectedUrl("/profile/contact-change/verification"));
        mvc.perform(post("/profile/contact-change/verify").session(session).with(csrf()).param("code",code))
                .andExpect(redirectedUrl("/profile/contact-change?type=EMAIL"));
        mvc.perform(post("/profile/contact-change/start").session(session).with(csrf()).param("type","EMAIL").param("value",newEmail))
                .andExpect(redirectedUrl("/profile/contact-change/verification"));
        mvc.perform(post("/profile/contact-change/verify").session(session).with(csrf()).param("code",code))
                .andExpect(redirectedUrl("/profile")).andExpect(authenticated());
        mvc.perform(get("/profile").session(session)).andExpect(status().isOk()).andExpect(authenticated());
        assertFalse(session.isInvalid());
        assertFalse(sessions.getSessionInformation(session.getId()).isExpired());
        assertNull(jdbc.queryForObject("SELECT provider_id FROM users WHERE id=?",String.class,owner));
        assertNull(jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,owner));
        assertTrue(users.findByGoogleIdentity(com.ddagtech.aureliabooks.entity.User.AuthProvider.GOOGLE,oldSub).isEmpty());
        var outcome=googleSignIn.resolve(new GoogleSignInService.Identity("new-"+marker,newEmail,true,null,"New Google",null),"new-login-session","127.0.0.1");
        assertEquals(owner,outcome.user().getId());assertTrue(outcome.newlyLinked());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Integer.class,newEmail));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE user_id=? AND action='GOOGLE_UNLINKED'",Integer.class,owner));
    }
}
