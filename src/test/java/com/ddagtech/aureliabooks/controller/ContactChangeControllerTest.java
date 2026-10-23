package com.ddagtech.aureliabooks.controller;
import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.ContactChangeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(ContactChangeController.class) @Import(SecurityConfig.class)
class ContactChangeControllerTest {
 @Autowired MockMvc mvc;
 @MockitoBean ContactChangeService changes;
 @MockitoBean com.ddagtech.aureliabooks.security.AuthenticatedSessionUpdater sessionUpdater;
 @MockitoBean CustomerIdentity identity;
 @MockitoBean com.ddagtech.aureliabooks.service.AccountSecurityService security;
 @Test void customerGetsFormsWithCsrfAndGuestIsRedirected()throws Exception{
  when(identity.resolve(any())).thenReturn(7L);
  when(security.contactSettings(7L)).thenReturn(new com.ddagtech.aureliabooks.dto.response.ContactSettingsView(true, false, false));
  mvc.perform(get("/profile/contact-change").with(user("customer").roles("CUSTOMER"))).andExpect(status().isOk())
   .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
  mvc.perform(get("/profile/contact-change")).andExpect(status().is3xxRedirection());
  mvc.perform(get("/profile/contact-change").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
 }
 @Test void postWithoutCsrfCannotStartVerification()throws Exception{
  mvc.perform(post("/profile/contact-change/start").with(user("customer").roles("CUSTOMER"))
   .param("type","EMAIL").param("value","new@example.com").param("channel","EMAIL")).andExpect(status().isForbidden());
  verifyNoInteractions(changes);
 }
 @Test void ownerComesFromAuthenticationNotPostedUserId()throws Exception{
  when(identity.resolve(any())).thenReturn(7L);
  when(changes.startDestination(eq(7L),anyString(),eq("EMAIL"),anyString(),anyString()))
   .thenReturn(new ContactChangeService.Result(false,"Đã gửi mã",false,true));
  when(changes.view(eq(7L),anyString())).thenReturn(new ContactChangeService.View("EMAIL","DESTINATION","n***@example.com",299,60,5));
  mvc.perform(post("/profile/contact-change/start").with(user("customer").roles("CUSTOMER")).with(csrf())
   .session(granted("EMAIL")).param("userId","99").param("type","EMAIL").param("value","new@example.com").param("channel","EMAIL"))
   .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/profile/contact-change/verification"));
  verify(changes).startDestination(eq(7L),anyString(),eq("EMAIL"),eq("new@example.com"),anyString());
  verifyNoInteractions(security);
 }
 @Test void committedChangeKeepsCurrentSessionAndRefreshesPrincipal()throws Exception{
  when(identity.resolve(any())).thenReturn(7L);
  when(changes.verify(eq(7L),anyString(),eq("123456"),anyString())).thenReturn(new ContactChangeService.Result(true,"Đã đổi thông tin"));
  var session=new MockHttpSession();
  mvc.perform(post("/profile/contact-change/verify").session(session).with(user("customer").roles("CUSTOMER")).with(csrf()).param("code","123456"))
   .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/profile"));
  assertThat(session.isInvalid()).isFalse();
  verify(sessionUpdater).refresh(eq(7L),any(),any());
 }
 @Test void wrongPasswordStopsBeforeSendingDestinationOtp()throws Exception{
  when(identity.resolve(any())).thenReturn(7L);doThrow(new org.springframework.security.authentication.BadCredentialsException("Sai mật khẩu")).when(security).reauthenticate(7L,"wrong",false);
  mvc.perform(post("/profile/contact-change/reauth").with(user("customer").roles("CUSTOMER")).with(csrf()).param("type","EMAIL").param("password","wrong"))
   .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("contactMessage","Sai mật khẩu"));
  verifyNoInteractions(changes);
 }
    @Test
    void firstContactAdditionReturnsToProfileWithoutInvalidatingSession() throws Exception {
        when(identity.resolve(any())).thenReturn(7L);
        when(changes.verify(eq(7L), anyString(), eq("123456"), anyString()))
                .thenReturn(new ContactChangeService.Result(true, "Đã bổ sung email.", false));
        var session = new MockHttpSession();
        session.setAttribute("retainedMarker", "customer-session");
        mvc.perform(post("/profile/contact-change/verify").session(session)
                        .with(user("customer").roles("CUSTOMER")).with(csrf()).param("code", "123456"))
                .andExpect(redirectedUrl("/profile"))
                .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated().withUsername("customer"))
                .andExpect(flash().attribute("successMessage", "Đã bổ sung email."));
        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getAttribute("retainedMarker")).isEqualTo("customer-session");
    }
 @Test void verificationPageContainsOnlyOtpStepAndCsrf()throws Exception{
  when(identity.resolve(any())).thenReturn(7L);
  when(changes.view(eq(7L),anyString())).thenReturn(new ContactChangeService.View("PHONE","DESTINATION","******6789",299,60,5));
  mvc.perform(get("/profile/contact-change/verification").with(user("customer").roles("CUSTOMER")))
   .andExpect(status().isOk()).andExpect(view().name("profile/contact-verification"))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("******6789")))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"code\"")))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
   .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"contactPassword\""))))
   .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"contactValue\""))));
 }
 @Test void entryPageDoesNotContainOtpEvenWithPendingRequest()throws Exception{
  when(identity.resolve(any())).thenReturn(7L);
  when(security.contactSettings(7L)).thenReturn(new com.ddagtech.aureliabooks.dto.response.ContactSettingsView(true, false, false));
  when(changes.view(eq(7L),anyString())).thenReturn(new ContactChangeService.View("EMAIL","DESTINATION","n***@example.com",299,60,5));
  mvc.perform(get("/profile/contact-change").with(user("customer").roles("CUSTOMER")))
   .andExpect(status().isOk())
   .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"contactOtp\""))));
 }
 @Test void verificationWithoutSessionBoundRequestReturnsToEntry()throws Exception{
  when(identity.resolve(any())).thenReturn(7L);
  mvc.perform(get("/profile/contact-change/verification").with(user("customer").roles("CUSTOMER")))
   .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/profile/contact-change"));
 }
 @Test void invalidOtpAndResendStayOnVerificationStep()throws Exception{
  when(identity.resolve(any())).thenReturn(7L);
  when(changes.verify(eq(7L),anyString(),eq("000000"),anyString())).thenReturn(new ContactChangeService.Result(false,"Mã xác thực không đúng."));
  when(changes.resend(eq(7L),anyString(),anyString())).thenReturn(new ContactChangeService.Result(false,"Đã gửi lại mã"));
  mvc.perform(post("/profile/contact-change/verify").with(user("customer").roles("CUSTOMER")).with(csrf()).param("code","000000"))
   .andExpect(redirectedUrl("/profile/contact-change/verification")).andExpect(flash().attribute("contactMessage","Mã xác thực không đúng."));
  mvc.perform(post("/profile/contact-change/resend").with(user("customer").roles("CUSTOMER")).with(csrf()))
   .andExpect(redirectedUrl("/profile/contact-change/verification"));
 }
    @Test
    void missingEmailOffersAdditionWithoutContactSelector() throws Exception {
        when(identity.resolve(any())).thenReturn(7L);
        when(security.contactSettings(7L)).thenReturn(new com.ddagtech.aureliabooks.dto.response.ContactSettingsView(true, false, true));
        mvc.perform(get("/profile/contact-change").param("type", "EMAIL")
                .with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("addingContact", true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"contactType\""))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("type=\"email\"")))
                .andExpect(model().attribute("selectedType", "EMAIL"));
        mvc.perform(get("/profile/contact-change").param("type", "PHONE")
                .with(user("customer").roles("CUSTOMER")))
                .andExpect(model().attribute("addingContact", false))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("type=\"tel\"")));
    }

    private MockHttpSession granted(String type) {
        var session = new MockHttpSession();
        session.setAttribute("CONTACT_REAUTH_OWNER", 7L);
        session.setAttribute("CONTACT_REAUTH_TYPE", type);
        session.setAttribute("CONTACT_REAUTH_UNTIL", java.time.Instant.now().plusSeconds(300));
        return session;
    }

    @Test void phoneReauthenticationUnlocksOnlyPhoneAndShowsVerifiedState() throws Exception {
        when(identity.resolve(any())).thenReturn(7L);
        when(security.contactSettings(7L)).thenReturn(new com.ddagtech.aureliabooks.dto.response.ContactSettingsView(true, true, true));
        var session = new MockHttpSession();
        mvc.perform(post("/profile/contact-change/reauth").session(session).with(user("customer").roles("CUSTOMER"))
                .with(csrf()).param("type", "PHONE").param("password", "valid-password"))
                .andExpect(redirectedUrl("/profile/contact-change?type=PHONE"));
        mvc.perform(get("/profile/contact-change").session(session).param("type", "PHONE").with(user("customer").roles("CUSTOMER")))
                .andExpect(model().attribute("reauthenticated", true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Đã xác thực.")));
        mvc.perform(get("/profile/contact-change").session(session).param("type", "EMAIL").with(user("customer").roles("CUSTOMER")))
                .andExpect(model().attribute("reauthenticated", false));
        String directory = System.getProperty("uc08.capture.dir");
        if (directory != null) {
            var page = mvc.perform(get("/profile/contact-change").session(session).param("type", "PHONE").with(user("customer").roles("CUSTOMER"))).andReturn();
            java.nio.file.Files.writeString(java.nio.file.Path.of(directory, "contact-ready.html"), page.getResponse().getContentAsString());
            var emailPage = mvc.perform(get("/profile/contact-change").session(granted("EMAIL")).param("type", "EMAIL")
                    .with(user("customer").roles("CUSTOMER"))).andExpect(model().attribute("reauthenticated", true)).andReturn();
            java.nio.file.Files.writeString(java.nio.file.Path.of(directory, "contact-email-ready.html"), emailPage.getResponse().getContentAsString());
        }
    }

    @Test void missingExpiredWrongOwnerOrWrongTypeProofCannotSendOtp() throws Exception {
        when(identity.resolve(any())).thenReturn(7L);
        var expired = granted("EMAIL");
        expired.setAttribute("CONTACT_REAUTH_UNTIL", java.time.Instant.now().minusSeconds(1));
        var otherOwner = granted("EMAIL");
        otherOwner.setAttribute("CONTACT_REAUTH_OWNER", 99L);
        for (var session : java.util.List.of(new MockHttpSession(), expired, otherOwner, granted("PHONE"))) {
            mvc.perform(post("/profile/contact-change/start").session(session).with(user("customer").roles("CUSTOMER"))
                    .with(csrf()).param("type", "EMAIL").param("value", "new@example.com"))
                    .andExpect(redirectedUrl("/profile/contact-change?type=EMAIL"))
                    .andExpect(flash().attributeExists("contactMessage"));
        }
        verifyNoInteractions(changes);
    }

    @Test void newContactFieldIsLockedBeforeReauthentication() throws Exception {
        when(identity.resolve(any())).thenReturn(7L);
        when(security.contactSettings(7L)).thenReturn(new com.ddagtech.aureliabooks.dto.response.ContactSettingsView(false, true, false));
        var result = mvc.perform(get("/profile/contact-change").param("type", "PHONE").with(user("customer").roles("CUSTOMER")))
                .andExpect(model().attribute("reauthenticated", false))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("disabled=\"disabled\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"type\" value=\"PHONE\""))).andReturn();
        String directory = System.getProperty("uc08.capture.dir");
        if (directory != null) java.nio.file.Files.writeString(java.nio.file.Path.of(directory, "contact-locked.html"), result.getResponse().getContentAsString());
    }

    @Test void reauthenticationAlsoRequiresCsrf() throws Exception {
        mvc.perform(post("/profile/contact-change/reauth").with(user("customer").roles("CUSTOMER"))
                .param("password", "ignored").param("type", "PHONE")).andExpect(status().isForbidden());
        verifyNoInteractions(security, changes);
    }

    @Test void failedNewRequestCannotJumpToAnOldEmailChallenge() throws Exception {
        when(identity.resolve(any())).thenReturn(7L);
        when(changes.startDestination(eq(7L), anyString(), eq("PHONE"), anyString(), anyString()))
                .thenReturn(new ContactChangeService.Result(false, "Vui lòng chờ đủ 60 giây."));
        when(changes.view(eq(7L), anyString())).thenReturn(new ContactChangeService.View("EMAIL", "DESTINATION", "a***@example.com", 200, 30, 5));
        mvc.perform(post("/profile/contact-change/start").session(granted("PHONE")).with(user("customer").roles("CUSTOMER"))
                .with(csrf()).param("type", "PHONE").param("value", "0912345678"))
                .andExpect(redirectedUrl("/profile/contact-change?type=PHONE"))
                .andExpect(flash().attribute("contactMessage", "Vui lòng chờ đủ 60 giây."));
    }

    @Test void unavailableEmailMethodIsNotRenderedButSmsAndPasswordAre() throws Exception {
        when(identity.resolve(any())).thenReturn(7L);
        when(security.contactSettings(7L)).thenReturn(new com.ddagtech.aureliabooks.dto.response.ContactSettingsView(true,false,true));
        when(changes.reauthenticationMethods(7L)).thenReturn(java.util.List.of("PASSWORD","SMS"));
        mvc.perform(get("/profile/contact-change").with(user("customer").roles("CUSTOMER")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"SMS\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("value=\"EMAIL\" required"))));
    }

    @Test void emailSourceOtpReturnsToTheRequestedPhoneChangeAfterProof() throws Exception {
        when(identity.resolve(any())).thenReturn(7L);
        when(changes.startReauthentication(eq(7L),anyString(),eq("PHONE"),eq("EMAIL"),anyString()))
                .thenReturn(new ContactChangeService.Result(false,"Đã gửi mã",false,true));
        var session=new MockHttpSession();
        mvc.perform(post("/profile/contact-change/reauth").session(session).with(user("customer").roles("CUSTOMER")).with(csrf())
                .param("type","PHONE").param("method","EMAIL")).andExpect(redirectedUrl("/profile/contact-change/verification"));
        when(changes.view(eq(7L),anyString())).thenReturn(new ContactChangeService.View("PHONE","CURRENT","a***@gmail.com",299,60,5));
        when(changes.verify(eq(7L),anyString(),eq("123456"),anyString()))
                .thenReturn(new ContactChangeService.Result(false,"Đã xác thực",false,false,true));
        mvc.perform(post("/profile/contact-change/verify").session(session).with(user("customer").roles("CUSTOMER")).with(csrf())
                .param("code","123456")).andExpect(redirectedUrl("/profile/contact-change?type=PHONE"));
        assertThat(session.getAttribute("CONTACT_REAUTH_TYPE")).isEqualTo("PHONE");
        verifyNoInteractions(sessionUpdater);
    }

    @Test void methodFormsAndOtpCountdownRenderForAvailableChannels() throws Exception {
        when(identity.resolve(any())).thenReturn(7L);
        when(security.contactSettings(7L)).thenReturn(new com.ddagtech.aureliabooks.dto.response.ContactSettingsView(true,true,true));
        var cases=java.util.Map.of("methods-all",java.util.List.of("PASSWORD","EMAIL","SMS"),
                "methods-phone",java.util.List.of("PASSWORD","SMS"),"methods-google",java.util.List.of("EMAIL"));
        for(var entry:cases.entrySet()) {
            when(changes.reauthenticationMethods(7L)).thenReturn(entry.getValue());
            var result=mvc.perform(get("/profile/contact-change").with(user("customer").roles("CUSTOMER")))
                    .andExpect(status().isOk()).andReturn();
            String html=result.getResponse().getContentAsString();
            assertThat(html.contains("name=\"method\" value=\"EMAIL\"" )).isEqualTo(entry.getValue().contains("EMAIL"));
            capture(entry.getKey()+".html",html);
        }
        when(changes.view(eq(7L),anyString())).thenReturn(new ContactChangeService.View("PHONE","CURRENT","a***@gmail.com",8,3,5));
        var otp=mvc.perform(get("/profile/contact-change/verification").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("data-expires-seconds=\"8\""))).andReturn();
        capture("contact-timer.html",otp.getResponse().getContentAsString());
    }

    private void capture(String name,String html) throws Exception {
        String directory=System.getProperty("uc08.capture.dir");
        if(directory!=null) java.nio.file.Files.writeString(java.nio.file.Path.of(directory,name),html);
    }
}
