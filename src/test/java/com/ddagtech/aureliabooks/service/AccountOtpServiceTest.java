package com.ddagtech.aureliabooks.service;
import com.ddagtech.aureliabooks.dto.request.RegisterRequest;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.UserRepository;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import java.util.*;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class AccountOtpServiceTest {
 jakarta.validation.ValidatorFactory vf;UserRepository users;AccountSecurityService writer;ContactOtpDelivery delivery;AccountOtpService service;String code;
 @BeforeEach void setup(){vf=Validation.buildDefaultValidatorFactory();users=mock(UserRepository.class);writer=mock(AccountSecurityService.class);delivery=mock(ContactOtpDelivery.class);var encoder=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);service=new AccountOtpService(users,vf.getValidator(),encoder,delivery,writer);doAnswer(a->{code=a.getArgument(2);return null;}).when(delivery).send(anyString(),anyString(),anyString());}
 @AfterEach void close(){vf.close();}
 RegisterRequest request(){return new RegisterRequest(null,"Abcd123!","Tên Khách","0912345678",null,"Abcd123!",null,true);}
 User account(){return User.builder().id(7L).email("a@example.test").emailVerified(true).phone("0912345678").phoneVerified(true).passwordHash("old-hash").build();}
 String reset(User user){when(users.findByIdentifierWithRoles("a@example.test")).thenReturn(Optional.of(user));return service.recovery("a@example.test","EMAIL","session","ip");}
 @Test void phoneRegistrationHasNoEmailAndWritesOnlyAfterProof(){String id=service.registration(request(),"session","ip");verifyNoInteractions(writer);assertThat(service.verify(id,"session",code,"ip").complete()).isTrue();verify(writer).register(argThat(d->d.phone().equals("0912345678")&&!d.passwordHash().equals("Abcd123!")),eq("ip"));assertThat(service.view(id,"session")).isNull();}
 @Test void wrongOwnerSessionAndPurposeCannotConsumeCode(){String id=service.registration(request(),"session","ip");assertThatThrownBy(()->service.verify(id,"other",code,"ip")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.reset(id,"session","Abcd123!","Abcd123!","ip")).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(writer);}
 @Test void fiveWrongCodesPreventCorrectCode(){String id=service.registration(request(),"s","ip");String correct=code,wrong=correct.equals("000000")?"111111":"000000";for(int i=0;i<5;i++)assertThatThrownBy(()->service.verify(id,"s",wrong,"ip")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.verify(id,"s",correct,"ip")).hasMessageContaining("hết lượt");verifyNoInteractions(writer);}
 @Test void expiredCodeCannotRegister()throws Exception{String id=service.registration(request(),"s","ip");var field=AccountOtpService.class.getDeclaredField("flows");field.setAccessible(true);@SuppressWarnings("unchecked") var flows=(Map<String,AccountOtpService.Flow>)field.get(service);flows.get(id).expires=Instant.now().minusSeconds(1);assertThatThrownBy(()->service.verify(id,"s",code,"ip")).hasMessageContaining("hết hạn");}
 @Test void newRegistrationCannotBypassTargetCooldown(){service.registration(request(),"s","ip");assertThatThrownBy(()->service.registration(request(),"s","ip")).hasMessageContaining("60 giây");verify(delivery,times(1)).send(anyString(),anyString(),anyString());}
 @Test void verifiedChannelsOnlyAndSingleChannelAutomatic(){User u=account();u.setEmailVerified(false);assertThat(reset(u)).isNotBlank();verify(delivery).send(eq("SMS"),eq(u.getPhone()),anyString());assertThat(service.recoveryChannels("a@example.test")).containsExactly("SMS");}
 @Test void googleOnlyInactiveAndUnknownCannotGetReset(){User u=account();u.setPasswordHash(null);String id=reset(u);assertThat(service.recoveryChannels("a@example.test")).isEmpty();assertThatThrownBy(()->service.verify(id,"session","123456","ip")).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(delivery,writer);u.setPasswordHash("hash");u.setIsActive(false);reset(u);verifyNoInteractions(delivery,writer);}
 @Test void resetRequiresOtpThenWritesOnceAndRejectsReplay(){String id=reset(account());assertThatThrownBy(()->service.reset(id,"session","NewPass1!","NewPass1!","ip")).isInstanceOf(IllegalArgumentException.class);assertThat(service.verify(id,"session",code,"ip").complete()).isFalse();service.reset(id,"session","NewPass1!","NewPass1!","ip");verify(writer).reset(eq(7L),eq("old-hash"),eq("EMAIL"),eq("a@example.test"),argThat(h->h.startsWith("$2")),eq("ip"));assertThatThrownBy(()->service.reset(id,"session","NewPass1!","NewPass1!","ip")).isInstanceOf(IllegalArgumentException.class);}
 @Test void invalidPasswordCannotConsumeVerifiedGrant(){String id=reset(account());service.verify(id,"session",code,"ip");assertThatThrownBy(()->service.reset(id,"session","weak","different","ip")).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(writer);service.reset(id,"session","NewPass1!","NewPass1!","ip");}
 @Test void deliveryFailureNeverAuthorizesRegistration(){doThrow(new IllegalStateException("secret")).when(delivery).send(anyString(),anyString(),anyString());assertThatThrownBy(()->service.registration(request(),"s","ip")).hasMessageNotContaining("secret");verifyNoInteractions(writer);}
 @Test void newRequestInvalidatesPreviousCodeForSameTarget()throws Exception{
  String first=service.registration(request(),"s","ip"),old=code;
  var field=AccountOtpService.class.getDeclaredField("limits");field.setAccessible(true);
  @SuppressWarnings("unchecked") var limits=(Map<String,AccountOtpService.Counter>)field.get(service);limits.get("target:0912345678").lastSent=Instant.now().minusSeconds(61);
  String next=service.registration(request(),"s","ip");assertThatThrownBy(()->service.verify(first,"s",old,"ip")).isInstanceOf(IllegalArgumentException.class);
  assertThat(service.verify(next,"s",code,"ip").complete()).isTrue();
 }
}
