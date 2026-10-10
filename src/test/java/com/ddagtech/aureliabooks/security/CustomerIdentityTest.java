package com.ddagtech.aureliabooks.security;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CustomerIdentityTest {
    @Test void googleUsesSubjectNotClientEmailAndRejectsInactive() {
        var users=mock(UserRepository.class);var google=mock(OidcUser.class);
        when(google.getSubject()).thenReturn("verified-subject");
        var role=new Role();role.setRoleName("ROLE_CUSTOMER");
        var user=User.builder().id(3L).isActive(true).role(role).build();
        when(users.findByGoogleIdentity(User.AuthProvider.GOOGLE,"verified-subject")).thenReturn(Optional.of(user));
        var auth=new UsernamePasswordAuthenticationToken(google,null,List.of());
        var identity=new CustomerIdentity(users);assertEquals(3L,identity.resolve(auth));
        user.setIsActive(false);assertThrows(AppException.class,()->identity.resolve(auth));
        verify(users,never()).findByEmail(any());
    }
}
