package com.ddagtech.aureliabooks.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RoleBasedAuthenticationSuccessHandlerTest {
    private final RoleBasedAuthenticationSuccessHandler handler=new RoleBasedAuthenticationSuccessHandler();

    @Test void customerReturnsToSameOriginSavedCart() throws Exception { assertRedirect("localhost","/cart","ROLE_CUSTOMER","http://localhost/cart?continue"); }
    @Test void customerDoesNotFollowExternalSavedRedirect() throws Exception { assertRedirect("attacker.example","/cart","ROLE_CUSTOMER","/"); }
    @Test void customerDoesNotReturnToAdminPage() throws Exception { assertRedirect("localhost","/admin/users","ROLE_CUSTOMER","/"); }
    @Test void staffAlwaysLandsOnDashboard() throws Exception { assertRedirect("localhost","/cart","ROLE_MANAGER","/dashboard"); }

    private void assertRedirect(String host,String path,String role,String expected) throws Exception {
        var saved=new MockHttpServletRequest("GET",path);
        saved.setServerName(host);
        var response=new MockHttpServletResponse();
        new HttpSessionRequestCache().saveRequest(saved,response);
        var login=new MockHttpServletRequest("POST","/auth/login");
        login.setSession(saved.getSession());
        var auth=UsernamePasswordAuthenticationToken.authenticated("member",null,List.of(new SimpleGrantedAuthority(role)));
        handler.onAuthenticationSuccess(login,response,auth);
        assertEquals(expected,response.getRedirectedUrl());
        assertNull(login.getSession().getAttribute("SPRING_SECURITY_SAVED_REQUEST"));
    }
}
