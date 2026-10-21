package com.ddagtech.aureliabooks.service;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.dto.request.RegisterRequest;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.session.SessionRegistry;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc @EnabledIfSystemProperty(named="accounts.mysql",matches="true")
class AccountFlowMySqlTest {
 @Autowired AccountOtpService otp;@Autowired AccountSecurityService security;@Autowired ContactChangeService contacts;
 @Autowired UserRepository users;@Autowired RegistrationService google;@Autowired JdbcTemplate jdbc;@Autowired PasswordEncoder passwords;@Autowired SessionRegistry sessions;
 @MockitoBean ContactOtpDelivery delivery;
 String code,phone,email;long id;
 @BeforeEach void setup(){assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).startsWith("authflow_qa_");phone="09"+String.format("%08d",new java.security.SecureRandom().nextInt(100000000));email=UUID.randomUUID()+"@example.test";doAnswer(a->{code=a.getArgument(2);return null;}).when(delivery).send(anyString(),anyString(),anyString());}
 @AfterEach void clean(){if(id>0){jdbc.update("DELETE FROM audit_logs WHERE user_id=?",id);jdbc.update("DELETE FROM users WHERE id=?",id);}}
 void register(){String flow=otp.registration(new RegisterRequest(null,"OldPass1!","Khách Hàng",phone,null,"OldPass1!",null,true),"s","ip");assertThat(users.findByPhone(phone)).isEmpty();otp.verify(flow,"s",code,"ip");id=users.findByPhone(phone).orElseThrow().getId();}
 @Test void registrationCreatesPhoneVerifiedCustomerWithoutEmailAndPhoneLoginPrincipal(){register();User u=users.findById(id).orElseThrow();assertThat(u.getEmail()).isNull();assertThat(u.isPhoneVerified()).isTrue();assertThat(u.isEmailVerified()).isFalse();assertThat(u.getRole().getRoleName()).isEqualTo("ROLE_CUSTOMER");assertThat(new CustomUserDetails(u).getUsername()).isEqualTo(phone);assertThat(passwords.matches("OldPass1!",u.getPasswordHash())).isTrue();}
 @Test void wrongCurrentPasswordCannotAuthorizeContactChange(){register();assertThatThrownBy(()->security.reauthenticate(id,"WrongPass1!",false)).isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);assertThat(users.findById(id).orElseThrow().getEmail()).isNull();}
 @Test void destinationEmailProofPersistsVerifiedAndKeepsSameAccount(){register();security.reauthenticate(id,"OldPass1!",false);contacts.startDestination(id,"s","EMAIL",email,"ip");assertThat(users.findById(id).orElseThrow().getEmail()).isNull();assertThat(contacts.verify(id,"s",code,"ip").completed()).isTrue();var u=users.findById(id).orElseThrow();assertThat(u.getEmail()).isEqualTo(email);assertThat(u.isEmailVerified()).isTrue();assertThat(u.getPhone()).isEqualTo(phone);}
 @Test void recoveryConsumesCodeResetsPasswordAndExpiresExistingSession(){security.register(new AccountSecurityService.Draft(phone,"Khách Hàng",null,User.Gender.OTHER,passwords.encode("OldPass1!")),"ip");id=users.findByPhone(phone).orElseThrow().getId();User u=users.findById(id).orElseThrow();sessions.registerNewSession("old-session",new CustomUserDetails(u));String flow=otp.recovery(phone,"EMAIL","s","ip");verify(delivery).send(eq("SMS"),eq(phone),anyString());otp.verify(flow,"s",code,"ip");otp.reset(flow,"s","NewPass1!","NewPass1!","ip");assertThat(passwords.matches("NewPass1!",users.findById(id).orElseThrow().getPasswordHash())).isTrue();assertThat(sessions.getSessionInformation("old-session").isExpired()).isTrue();assertThatThrownBy(()->otp.reset(flow,"s","NewPass1!","NewPass1!","ip")).isInstanceOf(IllegalArgumentException.class);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE user_id=? AND action='PASSWORD_RESET'",Integer.class,id)).isEqualTo(1);sessions.removeSessionInformation("old-session");}
 void linkFromVerifiedEmail(String sub){jdbc.update("UPDATE users SET email=?,email_verified=TRUE WHERE id=?",email,id);security.completeGoogleLink(id,sub,email,"AUTO",email,"ip");}
 @Test void googleLinksToLocalAccountAndNextGoogleLoginDoesNotCreateDuplicate(){register();String sub="sub-"+UUID.randomUUID();security.reauthenticate(id,"OldPass1!",false);linkFromVerifiedEmail(sub);var u=google.registerGoogle(sub,email,true,"Google Name",null);assertThat(u.getId()).isEqualTo(id);assertThat(u.getAuthProvider()).isEqualTo(User.AuthProvider.LOCAL);assertThat(u.getPhone()).isEqualTo(phone);assertThat(u.isEmailVerified()).isTrue();assertThat(passwords.matches("OldPass1!",u.getPasswordHash())).isTrue();}
 @Test void duplicateGoogleSubjectAndContactAreRejectedByDatabase(){register();String sub="sub-"+UUID.randomUUID();linkFromVerifiedEmail(sub);assertThatThrownBy(()->jdbc.update("INSERT INTO users(role_id,phone,email,password_hash,full_name,auth_provider,provider_id) VALUES(4,'0999999999','other@example.test','hash','Other','GOOGLE',?)",sub)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);assertThatThrownBy(()->jdbc.update("INSERT INTO users(role_id,phone,password_hash,full_name) VALUES(4,?,'hash','Other')",phone)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);}
}
