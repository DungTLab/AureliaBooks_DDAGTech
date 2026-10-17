package com.ddagtech.aureliabooks.security;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.service.AccountSecurityService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import java.util.*;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class GoogleAccountActionTest {
 AccountSecurityService security;GoogleAccountAction action;MockHttpServletRequest req;User user;
 @BeforeEach void setup(){security=mock(AccountSecurityService.class);action=new GoogleAccountAction(security);req=new MockHttpServletRequest();RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));user=User.builder().id(7L).providerId("sub-current").build();when(security.customer(7L)).thenReturn(user);}
 @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}
 DefaultOAuth2User claims(String sub){return new DefaultOAuth2User(List.of(),Map.of("sub",sub,"email","a@example.test","email_verified",true),"sub");}
 @Test void noIntentDoesNotLink(){assertThat(action.resolve(claims("sub-current"))).isNull();verifyNoInteractions(security);}
 @Test void reauthRequiresSameSubjectAndCreatesShortGrant(){GoogleAccountAction.begin(req.getSession(),7L,"REAUTH");assertThat(action.resolve(claims("sub-current"))).isSameAs(user);assertThat(req.getSession().getAttribute("GOOGLE_REAUTH_OWNER")).isEqualTo(7L);assertThat(action.resolve(claims("sub-current"))).isNull();verify(security,never()).linkGoogle(any(),any(),any(),anyBoolean(),any());}
 @Test void differentGoogleCannotReauthenticate(){GoogleAccountAction.begin(req.getSession(),7L,"REAUTH");assertThatThrownBy(()->action.resolve(claims("different"))).isInstanceOf(IllegalArgumentException.class);assertThat(req.getSession().getAttribute("GOOGLE_REAUTH_OWNER")).isNull();}
 @Test void expiredIntentCannotFallBackToCreatingAccount(){req.getSession().setAttribute("GOOGLE_ACCOUNT_ACTION",new GoogleAccountAction.Intent(7L,"LINK",Instant.now().minusSeconds(1)));assertThatThrownBy(()->action.resolve(claims("sub-current"))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(security);}
 @Test void linkUsesIntentOwnerAndDoesNotMergeByEmail(){GoogleAccountAction.begin(req.getSession(),7L,"LINK");when(security.linkGoogle(eq(7L),eq("new-sub"),eq("a@example.test"),eq(true),anyString())).thenReturn(user);assertThat(action.resolve(claims("new-sub"))).isSameAs(user);verify(security).linkGoogle(eq(7L),eq("new-sub"),eq("a@example.test"),eq(true),anyString());}
}
