package com.ddagtech.aureliabooks.service;
import com.ddagtech.aureliabooks.dto.request.RegisterRequest;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.exception.AppException;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.LocalDate;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrationServiceTest {
    UserRepository users; RoleRepository roles; ValidatorFactory factory; Validator validator;
    RegistrationService service;
    Role customer = Role.builder().id(1L).roleName("ROLE_CUSTOMER").build();
    @BeforeEach void setup() {
        users=mock(UserRepository.class); roles=mock(RoleRepository.class);
        factory=Validation.buildDefaultValidatorFactory(); validator=factory.getValidator();
        service=new RegistrationService(users,roles,new BCryptPasswordEncoder(4),validator);
        when(roles.findByRoleName("ROLE_CUSTOMER")).thenReturn(Optional.of(customer));
        when(users.saveAndFlush(any())).thenAnswer(i -> { User u=i.getArgument(0); u.setId(10L); return u; });
    }
    @AfterEach void close() { factory.close(); }
    RegisterRequest request(String password,String confirm,String phone,LocalDate dob) {
        return new RegisterRequest(" Test@Example.com ",password,"Nguyễn Văn Dũng",phone,dob,confirm,null,true);
    }
    @Test void localSavesHashAndExactlyOneCustomerRole() {
        assertEquals(10L,service.registerLocal(request("ABCD123!","ABCD123!","0912345678",null)));
        var capture=ArgumentCaptor.forClass(User.class); verify(users).saveAndFlush(capture.capture());
        User u=capture.getValue(); assertEquals("test@example.com",u.getEmail());
        assertSame(customer,u.getRole()); assertEquals(User.AuthProvider.LOCAL,u.getAuthProvider());
        assertTrue(new BCryptPasswordEncoder().matches("ABCD123!",u.getPasswordHash()));
        assertNull(u.getProviderId()); assertTrue(u.getIsActive());
    }
    @ParameterizedTest @ValueSource(strings={"Abc12!", "abc12345!", "ABCDEFGH!", "ABCDEFG12", " "})
    void rejectsInvalidPasswords(String password) {
        assertThrows(AppException.class,()->service.registerLocal(request(password,password,"0912345678",null)));
        verify(users,never()).saveAndFlush(any());
    }
    @ParameterizedTest @ValueSource(strings={"091234567", "09123456789", "a912345678", "1912345678"})
    void rejectsInvalidPhone(String phone) {
        assertFalse(validator.validate(request("Abcd123!","Abcd123!",phone,null)).isEmpty());
    }
    @Test void passwordMismatchAndBcryptByteLimitAreRejected() {
        assertFalse(validator.validate(request("Abcd123!","different","0912345678",null)).isEmpty());
        assertFalse(validator.validate(request("A1!"+"é".repeat(35),"A1!"+"é".repeat(35),"0912345678",null)).isEmpty());
        assertTrue(validator.validate(request("A1!"+"x".repeat(69),"A1!"+"x".repeat(69),"0912345678",null)).isEmpty());
    }
    @Test void optionalDobHasNoAgeRestrictionButMustBePast() {
        assertTrue(validator.validate(request("Abcd123!","Abcd123!","0912345678",LocalDate.now().minusDays(1))).isEmpty());
        assertFalse(validator.validate(request("Abcd123!","Abcd123!","0912345678",LocalDate.now())).isEmpty());
        assertFalse(validator.validate(request("Abcd123!","Abcd123!","0912345678",LocalDate.now().plusDays(1))).isEmpty());
    }
    @Test void duplicateEmailRejectedEvenIfInactive() {
        when(users.existsByEmail("test@example.com")).thenReturn(true);
        assertThrows(AppException.class,()->service.registerLocal(request("Abcd123!","Abcd123!","0912345678",null)));
        verify(users,never()).saveAndFlush(any());
    }
    @Test void duplicatePhoneRejected() {
        when(users.existsByPhone("0912345678")).thenReturn(true);
        assertThrows(AppException.class,()->service.registerLocal(request("Abcd123!","Abcd123!","0912345678",null)));
        verify(users,never()).saveAndFlush(any());
    }
    @Test void missingCustomerRoleDoesNotCreateRoleOrUser() {
        when(roles.findByRoleName("ROLE_CUSTOMER")).thenReturn(Optional.empty());
        assertThrows(AppException.class,()->service.registerLocal(request("Abcd123!","Abcd123!","0912345678",null)));
        verify(users,never()).saveAndFlush(any()); verify(roles,never()).save(any());
    }
    @Test void googleCreatesCustomerWithoutFabricatedPhonePasswordOrDob() {
        User u=service.registerGoogle("sub-123","Google@Example.com",true,"Tên Google","https://example.com/avatar");
        assertSame(customer,u.getRole()); assertEquals("google@example.com",u.getEmail());
        assertEquals("sub-123",u.getProviderId()); assertEquals(User.AuthProvider.GOOGLE,u.getAuthProvider());
        assertEquals("Tên Google",u.getFullName()); assertEquals("https://example.com/avatar",u.getAvatarUrl());
        assertNull(u.getPhone()); assertNull(u.getPasswordHash()); assertNull(u.getDob());
    }
    @Test void repeatedGoogleIdentityReturnsExistingWithoutInsertingOrChangingRole() {
        User existing=User.builder().id(5L).isActive(true).role(customer).build();
        when(users.findByGoogleIdentity(User.AuthProvider.GOOGLE,"sub-123")).thenReturn(Optional.of(existing));
        assertSame(existing,service.registerGoogle("sub-123","changed@example.com",true,null,null));
        verify(users,never()).saveAndFlush(any()); verify(users,never()).existsByEmail(any());
    }
    @Test void googleRejectsUnverifiedMissingSubjectDuplicateEmailAndInactiveIdentity() {
        assertThrows(AppException.class,()->service.registerGoogle("sub","a@example.com",false,null,null));
        assertThrows(AppException.class,()->service.registerGoogle(null,"a@example.com",true,null,null));
        assertThrows(AppException.class,()->service.registerGoogle("sub","invalid",true,null,null));
        when(users.existsByEmail("a@example.com")).thenReturn(true);
        assertThrows(AppException.class,()->service.registerGoogle("sub","a@example.com",true,null,null));
        when(users.findByGoogleIdentity(User.AuthProvider.GOOGLE,"sub")).thenReturn(Optional.of(User.builder().isActive(false).build()));
        assertThrows(AppException.class,()->service.registerGoogle("sub","a@example.com",true,null,null));
        verify(users,never()).saveAndFlush(any());
    }
    @Test void consentIsRequiredAndDtoDoesNotExposePasswordsInLogs() {
        var r=new RegisterRequest("a@example.com","Abcd123!","Dũng","0912345678",null,"Abcd123!",null,false);
        assertFalse(validator.validate(r).isEmpty()); assertFalse(r.toString().contains("Abcd123!"));
    }
}
