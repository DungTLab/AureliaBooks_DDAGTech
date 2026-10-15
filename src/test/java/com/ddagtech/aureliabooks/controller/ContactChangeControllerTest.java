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
 @MockitoBean CustomerIdentity identity;
 @MockitoBean com.ddagtech.aureliabooks.service.AccountSecurityService security;
 @Test void customerGetsFormsWithCsrfAndGuestIsRedirected()throws Exception{
  when(identity.resolve(any())).thenReturn(7L);
  when(security.customer(7L)).thenReturn(com.ddagtech.aureliabooks.entity.User.builder().passwordHash("hash").build());
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
   .thenReturn(new ContactChangeService.Result(false,"Đã gửi mã"));
  when(changes.view(eq(7L),anyString())).thenReturn(new ContactChangeService.View("EMAIL","DESTINATION","n***@example.com",299,60,5));
  mvc.perform(post("/profile/contact-change/start").with(user("customer").roles("CUSTOMER")).with(csrf())
   .param("userId","99").param("type","EMAIL").param("value","new@example.com").param("channel","EMAIL"))
   .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/profile/contact-change/verification"));
  verify(changes).startDestination(eq(7L),anyString(),eq("EMAIL"),eq("new@example.com"),anyString());
  verify(security).reauthenticate(7L,null,false);
 }
 @Test void committedChangeLogsOutCurrentSession()throws Exception{
  when(identity.resolve(any())).thenReturn(7L);
  when(changes.verify(eq(7L),anyString(),eq("123456"),anyString())).thenReturn(new ContactChangeService.Result(true,"Đã đổi thông tin"));
  var session=new MockHttpSession();
  mvc.perform(post("/profile/contact-change/verify").session(session).with(user("customer").roles("CUSTOMER")).with(csrf()).param("code","123456"))
   .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/login"));
  assertThat(session.isInvalid()).isTrue();
 }
 @Test void wrongPasswordStopsBeforeSendingDestinationOtp()throws Exception{
  when(identity.resolve(any())).thenReturn(7L);doThrow(new org.springframework.security.authentication.BadCredentialsException("Sai mật khẩu")).when(security).reauthenticate(7L,"wrong",false);
  mvc.perform(post("/profile/contact-change/start").with(user("customer").roles("CUSTOMER")).with(csrf()).param("type","EMAIL").param("value","new@example.com").param("password","wrong"))
   .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("contactMessage","Sai mật khẩu"));
  verifyNoInteractions(changes);
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
  when(security.customer(7L)).thenReturn(com.ddagtech.aureliabooks.entity.User.builder().passwordHash("hash").build());
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
}
