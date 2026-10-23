package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.service.impl.AccountOtpServiceImpl;
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
 @BeforeEach void setup(){vf=Validation.buildDefaultValidatorFactory();users=mock(UserRepository.class);writer=mock(AccountSecurityService.class);delivery=mock(ContactOtpDelivery.class);var encoder=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);service=new AccountOtpServiceImpl(users,vf.getValidator(),encoder,delivery,writer,new com.ddagtech.aureliabooks.security.LoginAttemptService());doAnswer(a->{code=a.getArgument(2);return null;}).when(delivery).send(anyString(),anyString(),anyString());}
 @AfterEach void close(){vf.close();}
 RegisterRequest request(){return new RegisterRequest(null,"Abcd123!","Tên Khách","0912345678",null,"Abcd123!",null,true);}
 User account(){return User.builder().id(7L).email("a@example.test").emailVerified(true).phone("0912345678").phoneVerified(true).passwordHash("old-hash").build();}
 String reset(User user){when(users.findByIdentifierWithRoles("a@example.test")).thenReturn(Optional.of(user));return service.recovery("a@example.test","EMAIL","session","ip");}
 @Test void phoneRegistrationHasNoEmailAndWritesOnlyAfterProof(){String id=service.registration(request(),"session","ip");verifyNoInteractions(writer);assertThat(service.verify(id,"session",code,"ip").complete()).isTrue();verify(writer).register(argThat(d->d.phone().equals("0912345678")&&!d.passwordHash().equals("Abcd123!")),eq("ip"));assertThat(service.view(id,"session")).isNull();}
 @Test void wrongOwnerSessionAndPurposeCannotConsumeCode(){String id=service.registration(request(),"session","ip");assertThatThrownBy(()->service.verify(id,"other",code,"ip")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.reset(id,"session","Abcd123!","Abcd123!","ip")).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(writer);}
 @Test void fiveWrongCodesPreventCorrectCode(){String id=service.registration(request(),"s","ip");String correct=code,wrong=correct.equals("000000")?"111111":"000000";for(int i=0;i<5;i++)assertThatThrownBy(()->service.verify(id,"s",wrong,"ip")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.verify(id,"s",correct,"ip")).hasMessageContaining("hết lượt");verifyNoInteractions(writer);}
 @Test void expiredCodeCannotRegister()throws Exception{String id=service.registration(request(),"s","ip");var field=AccountOtpServiceImpl.class.getDeclaredField("flows");field.setAccessible(true);@SuppressWarnings("unchecked") var flows=(Map<String,?>)field.get(service);var flow=flows.get(id);var expiry=flow.getClass().getDeclaredField("expires");expiry.setAccessible(true);expiry.set(flow,Instant.now().minusSeconds(1));assertThatThrownBy(()->service.verify(id,"s",code,"ip")).hasMessageContaining("hết hạn");}
 @Test void newRegistrationCannotBypassTargetCooldown(){service.registration(request(),"s","ip");assertThatThrownBy(()->service.registration(request(),"s","ip")).hasMessageContaining("60 giây");verify(delivery,times(1)).send(anyString(),anyString(),anyString());}
 @Test void verifiedSelectedChannelNeverFallsBackToAnotherContact(){
  User u=account();u.setEmailVerified(false);
  when(users.findByIdentifierWithRoles("a@example.test")).thenReturn(Optional.of(u));
  assertThatThrownBy(()->reset(u)).hasMessageContaining("Kênh");
  verifyNoInteractions(delivery);
  assertThat(service.recovery("a@example.test","SMS","session","ip")).isNotBlank();
  verify(delivery).send(eq("SMS"),eq(u.getPhone()),anyString());
  assertThat(service.recoveryChannels("a@example.test")).containsExactly("SMS");
 }
 @Test void googleOnlyInactiveAndUnknownCannotGetReset(){
  assertThatThrownBy(()->service.recovery("missing@example.test","EMAIL","session","ip")).hasMessageContaining("Không tìm thấy tài khoản");
  User u=account();u.setPasswordHash(null);when(users.findByIdentifierWithRoles("a@example.test")).thenReturn(Optional.of(u));
  assertThatThrownBy(()->service.recoveryChannels("a@example.test")).hasMessageContaining("Google");
  assertThatThrownBy(()->reset(u)).hasMessageContaining("Google");
  u.setPasswordHash("hash");u.setIsActive(false);assertThatThrownBy(()->reset(u)).hasMessageContaining("vô hiệu hóa");
  verifyNoInteractions(delivery,writer);
 }
 @Test void unknownLookupAndInvalidFormatCannotOfferChannels(){
  assertThatThrownBy(()->service.recoveryChannels("missing@example.test")).hasMessageContaining("Không tìm thấy tài khoản");
  assertThatThrownBy(()->service.recoveryChannels("not-an-email-or-phone")).hasMessageContaining("hợp lệ");
  verify(users,never()).findByIdentifierWithRoles("not-an-email-or-phone");verifyNoInteractions(delivery,writer);
 }
 @Test void accountWithoutVerifiedChannelsCannotStartRecovery(){
  User u=account();u.setEmailVerified(false);u.setPhoneVerified(false);when(users.findByIdentifierWithRoles("a@example.test")).thenReturn(Optional.of(u));
  assertThatThrownBy(()->service.recoveryChannels("a@example.test")).hasMessageContaining("chưa có");
  assertThatThrownBy(()->reset(u)).hasMessageContaining("chưa có");verifyNoInteractions(delivery,writer);
 }
 @Test void failedRecoveryDeliveryThrowsAndDoesNotRetainFlow()throws Exception{
  when(users.findByIdentifierWithRoles("a@example.test")).thenReturn(Optional.of(account()));
  doThrow(new IllegalStateException("provider-secret")).when(delivery).send(anyString(),anyString(),anyString());
  assertThatThrownBy(()->service.recovery("a@example.test","EMAIL","session","ip")).hasMessageContaining("Không gửi được mã").hasMessageNotContaining("provider-secret");
  var field=AccountOtpServiceImpl.class.getDeclaredField("flows");field.setAccessible(true);assertThat((Map<?,?>)field.get(service)).isEmpty();verifyNoInteractions(writer);
 }
 @Test void recoveryRejectsTamperedChannelAndRechecksLatestAccountState(){
  when(users.findByIdentifierWithRoles("a@example.test")).thenReturn(Optional.of(account()));
  assertThatThrownBy(()->service.recovery("a@example.test","WHATSAPP","session","ip")).hasMessageContaining("Kênh");
  var u=account();u.setIsActive(false);when(users.findByIdentifierWithRoles("a@example.test")).thenReturn(Optional.of(u));
  assertThatThrownBy(()->service.recovery("a@example.test","EMAIL","session","ip")).hasMessageContaining("vô hiệu hóa");verifyNoInteractions(delivery,writer);
 }
 @Test void resetRequiresOtpThenWritesOnceAndRejectsReplay(){String id=reset(account());assertThatThrownBy(()->service.reset(id,"session","NewPass1!","NewPass1!","ip")).isInstanceOf(IllegalArgumentException.class);assertThat(service.verify(id,"session",code,"ip").complete()).isFalse();service.reset(id,"session","NewPass1!","NewPass1!","ip");verify(writer).reset(eq(7L),eq("old-hash"),eq("EMAIL"),eq("a@example.test"),argThat(h->h.startsWith("$2")),eq("ip"));assertThatThrownBy(()->service.reset(id,"session","NewPass1!","NewPass1!","ip")).isInstanceOf(IllegalArgumentException.class);}
 @Test void invalidPasswordCannotConsumeVerifiedGrant(){String id=reset(account());service.verify(id,"session",code,"ip");assertThatThrownBy(()->service.reset(id,"session","weak","different","ip")).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(writer);service.reset(id,"session","NewPass1!","NewPass1!","ip");}
 @Test void deliveryFailureNeverAuthorizesRegistration(){doThrow(new IllegalStateException("secret")).when(delivery).send(anyString(),anyString(),anyString());assertThatThrownBy(()->service.registration(request(),"s","ip")).hasMessageNotContaining("secret");verifyNoInteractions(writer);}
 @Test void newRequestInvalidatesPreviousCodeForSameTarget()throws Exception{
  String first=service.registration(request(),"s","ip"),old=code;
  var field=AccountOtpServiceImpl.class.getDeclaredField("limits");field.setAccessible(true);
  @SuppressWarnings("unchecked") var limits=(Map<String,?>)field.get(service);var counter=limits.get("target:0912345678");var sent=counter.getClass().getDeclaredField("lastSent");sent.setAccessible(true);sent.set(counter,Instant.now().minusSeconds(61));
  String next=service.registration(request(),"s","ip");assertThatThrownBy(()->service.verify(first,"s",old,"ip")).isInstanceOf(IllegalArgumentException.class);
  assertThat(service.verify(next,"s",code,"ip").complete()).isTrue();
 }
}
