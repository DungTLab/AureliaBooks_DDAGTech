package com.ddagtech.aureliabooks.controller;
import com.ddagtech.aureliabooks.service.AccountOtpService;
import com.ddagtech.aureliabooks.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@WebMvcTest(AccountVerificationController.class) @Import(SecurityConfig.class)
class AccountVerificationControllerTest {
 @Autowired MockMvc mvc;@MockitoBean AccountOtpService otp;
 @Test void forgotPasswordIsPublicAndAllPostsRequireCsrf()throws Exception{
  mvc.perform(get("/auth/forgot-password")).andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")));
  for(String path:List.of("/auth/forgot-password/lookup","/auth/forgot-password/send","/auth/verification/verify","/auth/verification/resend","/auth/verification/reset"))mvc.perform(post(path)).andExpect(status().isForbidden());
  verifyNoInteractions(otp);
 }
 @Test void singleVerifiedChannelHasNoSelectorWhileTwoHaveChoice()throws Exception{
  when(otp.recoveryChannels("0912345678")).thenReturn(List.of("SMS"));
  mvc.perform(post("/auth/forgot-password/lookup").with(csrf()).param("identifier","0912345678")).andExpect(status().isOk()).andExpect(content().string(not(containsString("id=\"recoveryChannel\""))));
  when(otp.recoveryChannels("0912345678")).thenReturn(List.of("EMAIL","SMS"));
  mvc.perform(post("/auth/forgot-password/lookup").with(csrf()).param("identifier","0912345678")).andExpect(status().isOk()).andExpect(content().string(containsString("id=\"recoveryChannel\"")));
 }
 @Test void verificationUsesOnlySessionFlowAndCannotEchoSubmittedPassword()throws Exception{
  var s=new MockHttpSession();s.setAttribute("ACCOUNT_OTP_FLOW","trusted-flow");
  when(otp.verify(eq("trusted-flow"),anyString(),eq("123456"),anyString())).thenReturn(new AccountOtpService.Result(false,"Verified"));
  mvc.perform(post("/auth/verification/verify").session(s).with(csrf()).param("flow","attacker-flow").param("code","123456")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/verification"));
  verify(otp).verify(eq("trusted-flow"),eq(s.getId()),eq("123456"),anyString());
 }
 @Test void resetWithoutVerifiedGrantShowsErrorWithoutInvokingLogin()throws Exception{
  var s=new MockHttpSession();s.setAttribute("ACCOUNT_OTP_FLOW","flow");doThrow(new IllegalArgumentException("Vui lòng xác minh mã trước")).when(otp).reset(anyString(),anyString(),anyString(),anyString(),anyString());
  mvc.perform(post("/auth/verification/reset").session(s).with(csrf()).param("password","NewPass1!").param("confirmation","NewPass1!")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/auth/verification")).andExpect(flash().attribute("verificationMessage","Vui lòng xác minh mã trước"));
 }
}
