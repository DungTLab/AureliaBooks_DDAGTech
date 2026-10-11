package com.ddagtech.aureliabooks.service;
import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.impl.UserServiceImpl;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.transaction.support.*;
import java.time.LocalDate;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileServiceTest {
    jakarta.validation.ValidatorFactory validatorFactory;
    UserRepository users; UserServiceImpl service; User user; SessionRegistry sessions;
    @BeforeEach void setup() {
        users=mock(UserRepository.class); Role role=new Role(); role.setRoleName("ROLE_CUSTOMER");
        user=User.builder().id(1L).fullName("Nguyễn Văn An").email("an@example.com").phone("0912345678")
            .dob(LocalDate.of(2005,1,1)).gender(User.Gender.MALE).authProvider(User.AuthProvider.LOCAL)
            .isActive(true).role(role).passwordHash(new BCryptPasswordEncoder().encode("OldPass1!")).build();
        when(users.findOwnerForUpdate(1L)).thenReturn(Optional.of(user));
        sessions=mock(SessionRegistry.class);
        validatorFactory=Validation.buildDefaultValidatorFactory();
        service=new UserServiceImpl(users,mock(RegistrationService.class),new BCryptPasswordEncoder(),
                validatorFactory.getValidator(),mock(AvatarStorage.class),sessions);
    }
    @AfterEach void closeValidatorFactory() {
        if (validatorFactory != null) validatorFactory.close();
    }
    @Test void rejectsTamperedExistingPhoneAndDob() {
        assertThrows(AppException.class,()->service.updateProfile(1L,new ProfileUpdateRequest("Nguyễn An","0987654321",null,User.Gender.MALE)));
        assertThrows(AppException.class,()->service.updateProfile(1L,new ProfileUpdateRequest("Nguyễn An",null,LocalDate.of(2006,1,1),User.Gender.MALE)));
        assertEquals("0912345678",user.getPhone());
    }
    @Test void googleCompletesPhoneOnceAndRejectsDuplicate() {
        user.setAuthProvider(User.AuthProvider.GOOGLE); user.setPhone(null);
        when(users.existsByPhone("0987654321")).thenReturn(true);
        assertThrows(AppException.class,()->service.updateProfile(1L,new ProfileUpdateRequest("Nguyễn An","0987654321",null,User.Gender.OTHER)));
        when(users.existsByPhone("0987654321")).thenReturn(false);
        service.updateProfile(1L,new ProfileUpdateRequest("Nguyễn An","0987654321",null,User.Gender.OTHER));
        assertEquals("0987654321",user.getPhone());
        assertThrows(AppException.class,()->service.updateProfile(1L,new ProfileUpdateRequest("Nguyễn An","0901234567",null,User.Gender.OTHER)));
    }
    @Test void incorrectOldPasswordAndWeakOrMismatchedNewPasswordDoNotWrite() {
        for(var request: java.util.List.of(new PasswordChangeRequest("wrong","NewPass1!","NewPass1!"),
                new PasswordChangeRequest("OldPass1!","weak","weak"),
                new PasswordChangeRequest("OldPass1!","NewPass1!","different"))) {
            assertThrows(AppException.class,()->service.changePassword(1L,request));
        }
        verify(users,never()).saveAndFlush(any());
    }
    @Test void googleMayLeaveDobBlankThenCompleteOnceWithoutAgeRestriction() {
        user.setAuthProvider(User.AuthProvider.GOOGLE); user.setDob(null);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        assertTrue(service.viewProfile(1L).canCompleteDob());
        service.updateProfile(1L,new ProfileUpdateRequest("Nguyễn An",null,null,User.Gender.OTHER));
        assertNull(user.getDob());
        var dob=LocalDate.now().minusDays(1);
        service.updateProfile(1L,new ProfileUpdateRequest("Nguyễn An",null,dob,User.Gender.OTHER));
        assertEquals(dob,user.getDob());
        assertFalse(service.viewProfile(1L).canCompleteDob());
        assertThrows(AppException.class,()->service.updateProfile(1L,
                new ProfileUpdateRequest("Nguyễn An",null,dob.minusDays(1),User.Gender.OTHER)));
        // Omitting a locked field must preserve the persisted value.
        service.updateProfile(1L,new ProfileUpdateRequest("Nguyễn An",null,null,User.Gender.OTHER));
        assertEquals(dob,user.getDob());
    }
    @Test void googleCannotCompleteTodayOrFutureDob() {
        user.setAuthProvider(User.AuthProvider.GOOGLE); user.setDob(null);
        for(var dob:java.util.List.of(LocalDate.now(),LocalDate.now().plusDays(1))) {
            assertThrows(AppException.class,()->service.updateProfile(1L,
                    new ProfileUpdateRequest("Nguyễn An",null,dob,User.Gender.OTHER)));
        }
        assertNull(user.getDob());
        verify(users,never()).saveAndFlush(any());
    }
    @Test void localAccountCannotCompleteMissingDob() {
        user.setDob(null);
        assertThrows(AppException.class,()->service.updateProfile(1L,
                new ProfileUpdateRequest("Nguyễn An",null,LocalDate.now().minusDays(1),User.Gender.MALE)));
        assertNull(user.getDob());
    }
    @Test void passwordChangeStoresBcryptAndRegistersSessionExpiryOnlyAfterCommit() {
        var principal=new com.ddagtech.aureliabooks.security.CustomUserDetails(user);
        var session=new org.springframework.security.core.session.SessionInformation(principal,"old-session",new java.util.Date());
        when(sessions.getAllPrincipals()).thenReturn(java.util.List.of(principal));
        when(sessions.getAllSessions(principal,false)).thenReturn(java.util.List.of(session));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.changePassword(1L,new PasswordChangeRequest("OldPass1!","NewPass1!","NewPass1!"));
            assertTrue(new BCryptPasswordEncoder().matches("NewPass1!",user.getPasswordHash()));
            assertFalse(new BCryptPasswordEncoder().matches("OldPass1!",user.getPasswordHash()));
            assertEquals(1,TransactionSynchronizationManager.getSynchronizations().size());
            assertFalse(session.isExpired());
            TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();
            assertTrue(session.isExpired());
        } finally {TransactionSynchronizationManager.clearSynchronization();}
    }
    @Test void googleWithoutLocalPasswordCannotSetPasswordBySupplyingOldPassword() {
        user.setPasswordHash(null);
        assertThrows(AppException.class,()->service.changePassword(1L,new PasswordChangeRequest("OldPass1!","NewPass1!","NewPass1!")));
    }
    @Test void inactiveCustomerCannotMutateProfile() {
        user.setIsActive(false);
        assertThrows(AppException.class,()->service.updateProfile(1L,new ProfileUpdateRequest("Nguyễn An",null,null,User.Gender.MALE)));
    }
}
