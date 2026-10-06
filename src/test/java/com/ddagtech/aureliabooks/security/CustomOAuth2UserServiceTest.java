package com.ddagtech.aureliabooks.security;
import com.ddagtech.aureliabooks.service.RegistrationService;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.exception.AppException;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import java.time.Instant;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomOAuth2UserServiceTest {
    RegistrationService registration; MockRestServiceServer server; CustomOAuth2UserService service;
    @BeforeEach void setup() {
        registration=mock(RegistrationService.class);
        RestTemplate rest=new RestTemplate(); server=MockRestServiceServer.createServer(rest);
        var delegate=new DefaultOAuth2UserService(); delegate.setRestOperations(rest);
        service=new CustomOAuth2UserService(registration,delegate);
    }
    OAuth2UserRequest request() {
        var client=ClientRegistration.withRegistrationId("google").clientId("test-client").clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/google").scope("profile","email")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo").userNameAttributeName("sub").build();
        return new OAuth2UserRequest(client,new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "test-access-token",Instant.now(),Instant.now().plusSeconds(60)));
    }
    void claims() {
        server.expect(requestTo("https://www.googleapis.com/oauth2/v3/userinfo"))
                .andExpect(header("Authorization","Bearer test-access-token"))
                .andRespond(withSuccess("{\"sub\":\"google-sub\",\"email\":\"a@example.com\",\"email_verified\":true,\"name\":\"Google User\",\"picture\":\"https://example.com/picture\"}",MediaType.APPLICATION_JSON));
    }
    @Test void fetchesTrustedClaimsAndUsesOnlyDatabaseRoleAuthority() {
        claims(); when(registration.registerGoogle("google-sub","a@example.com",true,"Google User","https://example.com/picture"))
                .thenReturn(User.builder().role(Role.builder().roleName("ROLE_CUSTOMER").build()).build());
        var principal=service.loadUser(request());
        assertEquals("google-sub",principal.getName());
        assertEquals(1,principal.getAuthorities().size());
        assertEquals("ROLE_CUSTOMER",principal.getAuthorities().iterator().next().getAuthority()); server.verify();
    }
    @Test void businessRejectionBecomesOAuthFailure() {
        claims(); when(registration.registerGoogle(any(),any(),any(),any(),any())).thenThrow(new AppException(ErrorCode.ACCOUNT_LOCKED));
        var error=assertThrows(OAuth2AuthenticationException.class,()->service.loadUser(request()));
        assertEquals("registration_failed",error.getError().getErrorCode());
        assertEquals(ErrorCode.ACCOUNT_LOCKED.getMessage(),error.getMessage()); server.verify();
    }
    @Test void databaseRaceFailsSafelyWithNoSqlDetails() {
        claims(); when(registration.registerGoogle(any(),any(),any(),any(),any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("private SQL"));
        var error=assertThrows(OAuth2AuthenticationException.class,()->service.loadUser(request()));
        assertFalse(error.getMessage().contains("private SQL")); server.verify();
    }
    @Test void oidcClaimsCreateDatabaseRolePrincipalAndRetainValidatedIdToken() {
        org.springframework.security.oauth2.client.userinfo.OAuth2UserService<
                org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest,
                org.springframework.security.oauth2.core.oidc.user.OidcUser> oidc = mock(
                        org.springframework.security.oauth2.client.userinfo.OAuth2UserService.class);
        var id = new org.springframework.security.oauth2.core.oidc.OidcIdToken("test-id-token", Instant.now(),
                Instant.now().plusSeconds(60), java.util.Map.of("sub", "google-sub", "email", "a@example.com",
                "email_verified", true, "name", "Google User"));
        var oidcRequest = new org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest(
                request().getClientRegistration(), request().getAccessToken(), id);
        when(oidc.loadUser(oidcRequest)).thenReturn(new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("OIDC_USER")), id));
        when(registration.registerGoogle("google-sub", "a@example.com", true, "Google User", null))
                .thenReturn(User.builder().role(Role.builder().roleName("ROLE_CUSTOMER").build()).build());
        var result = new CustomOAuth2UserService(registration, new DefaultOAuth2UserService(), oidc).loadOidcUser(oidcRequest);
        assertSame(id, result.getIdToken());
        assertEquals("ROLE_CUSTOMER", result.getAuthorities().iterator().next().getAuthority());
        assertEquals(1, result.getAuthorities().size());
    }
}
