package com.ddagtech.aureliabooks.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.response.ProfileView;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PasswordSetupController.class)
@Import(SecurityConfig.class)
class PasswordSetupControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CustomerIdentity identity;

    @MockitoBean
    UserService users;

    @MockitoBean
    ContactChangeService changes;

    @BeforeEach
    void setup() {
        when(identity.resolve(any())).thenReturn(7L);
        when(users.viewProfile(7L)).thenReturn(
            new ProfileView(7L, "a@example.test", "An Nguyen", null, null, "OTHER", null, true, false, true)
        );
        when(changes.reauthenticationMethods(7L)).thenReturn(List.of("EMAIL"));
    }

    MockHttpSession grant(String purpose, long seconds) {
        var session = new MockHttpSession();
        session.setAttribute("CONTACT_REAUTH_OWNER", 7L);
        session.setAttribute("CONTACT_REAUTH_TYPE", purpose);
        session.setAttribute("CONTACT_REAUTH_UNTIL", Instant.now().plusSeconds(seconds));
        return session;
    }

    @Test
    void noOtpExpiredOrWrongPurposeCannotCreatePassword() throws Exception {
        var wrongOwner = grant("PASSWORD_SETUP", 300);
        wrongOwner.setAttribute("CONTACT_REAUTH_OWNER", 8L);
        for (var session : List.of(
            new MockHttpSession(),
            grant("EMAIL", 300),
            grant("PASSWORD_SETUP", -1),
            wrongOwner
        )) {
            mvc.perform(
                post("/profile/password/setup")
                    .with(user("customer").roles("CUSTOMER"))
                    .session(session)
                    .with(csrf())
                    .param("newPassword", "NewPass1!")
                    .param("confirmPassword", "NewPass1!")
            ).andExpect(redirectedUrl("/profile/password/setup"));
        }
        verify(users, never()).setupPassword(any(), any(), any());
    }

    @Test
    void csrfAndRoleAreEnforced() throws Exception {
        mvc.perform(post("/profile/password/setup").with(user("customer").roles("CUSTOMER"))).andExpect(
            status().isForbidden()
        );
        mvc.perform(get("/profile/password/setup").with(user("admin").roles("ADMIN"))).andExpect(
            status().isForbidden()
        );
    }

    @Test
    void weakOrMismatchedPasswordCannotUseValidProof() throws Exception {
        for (String password : List.of("weak", "x".repeat(73) + "A1!")) {
            mvc.perform(
                post("/profile/password/setup")
                    .session(grant("PASSWORD_SETUP", 300))
                    .with(user("customer").roles("CUSTOMER"))
                    .with(csrf())
                    .param("newPassword", password)
                    .param("confirmPassword", password)
            ).andExpect(redirectedUrl("/profile/password/setup"));
        }
        mvc.perform(
            post("/profile/password/setup")
                .session(grant("PASSWORD_SETUP", 300))
                .with(user("customer").roles("CUSTOMER"))
                .with(csrf())
                .param("newPassword", "NewPass1!")
                .param("confirmPassword", "Different1!")
        ).andExpect(redirectedUrl("/profile/password/setup"));
        verify(users, never()).setupPassword(any(), any(), any());
    }

    @Test
    void validProofCreatesPasswordThenEndsLogin() throws Exception {
        mvc.perform(
            post("/profile/password/setup")
                .session(grant("PASSWORD_SETUP", 300))
                .with(user("customer").roles("CUSTOMER"))
                .with(csrf())
                .param("newPassword", "NewPass1!")
                .param("confirmPassword", "NewPass1!")
        ).andExpect(redirectedUrl("/auth/login?passwordChanged"));
        verify(users).setupPassword(eq(7L), argThat(r -> "NewPass1!".equals(r.newPassword())), anyString());
    }

    @Test
    void setupFormRequiresProofBeforeShowingPasswords() throws Exception {
        var start = mvc
            .perform(get("/profile/password/setup").with(user("customer").roles("CUSTOMER")))
            .andExpect(status().isOk())
            .andExpect(
                content().string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"setupPassword\""))
                )
            )
            .andReturn();
        var ready = mvc
            .perform(
                get("/profile/password/setup")
                    .session(grant("PASSWORD_SETUP", 300))
                    .with(user("customer").roles("CUSTOMER"))
            )
            .andExpect(status().isOk())
            .andReturn();
        String directory = System.getProperty("uc08.capture.dir");
        if (directory != null) {
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(directory, "password-setup-start.html"),
                start.getResponse().getContentAsString()
            );
            java.nio.file.Files.writeString(
                java.nio.file.Path.of(directory, "password-setup-ready.html"),
                ready.getResponse().getContentAsString()
            );
        }
    }
}
