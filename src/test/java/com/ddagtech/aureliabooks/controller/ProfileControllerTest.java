package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.response.*;
import com.ddagtech.aureliabooks.entity.ShippingAddress;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProfileController.class)
@Import({SecurityConfig.class, com.ddagtech.aureliabooks.security.CustomAccessDeniedHandler.class,
        AccountPresentationService.class})
class ProfileControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserService users;
    @MockitoBean AddressService addresses;
    @MockitoBean CustomerIdentity identity;
    @MockitoBean AvatarStorage avatars;
    @MockitoBean com.ddagtech.aureliabooks.repository.UserRepository navbarUsers;
    @BeforeEach void setup() {
        when(identity.resolve(any())).thenReturn(7L);
        when(users.viewProfile(7L)).thenReturn(new ProfileView(7L,"an@example.com","Nguyễn An","0912345678",
                java.time.LocalDate.of(2000,1,1),"MALE",null,false,true,false));
        when(addresses.list(7L)).thenReturn(List.of(new AddressView(9L,"Nguyễn An","0912345678",
                ShippingAddress.EconomicRegion.NORTHERN,"Hà Nội","Ba Đình","Đội Cấn","12 Đội Cấn",true)));
    }
    @Test void profileAndAddressFormsRenderWithCsrfAndImmutableFields() throws Exception {
        var profile=mvc.perform(get("/profile").with(user("customer").roles("CUSTOMER")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Thông tin cá nhân")))
            .andExpect(content().string(containsString("name=\"_csrf\"")))
            .andExpect(content().string(not(containsString("name=\"dob\""))))
            .andExpect(content().string(not(containsString("name=\"phone\"")))).andReturn();
        var book=mvc.perform(get("/profile/addresses").with(user("customer").roles("CUSTOMER")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("12 Đội Cấn")))
            .andExpect(content().string(containsString("name=\"_csrf\""))).andReturn();
        String directory=System.getProperty("uc08.capture.dir");
        if(directory!=null) {
            var dir=java.nio.file.Path.of(directory);java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("profile.html"),profile.getResponse().getContentAsString());
            java.nio.file.Files.writeString(dir.resolve("addresses.html"),book.getResponse().getContentAsString());
        }
    }
    @Test void googleMissingPhoneHasCompletionInputAndNoPasswordForm() throws Exception {
        when(users.viewProfile(7L)).thenReturn(new ProfileView(7L,"google@example.com","Nguyễn An",null,null,"OTHER",null,true,false,true));
        mvc.perform(get("/profile").with(user("customer").roles("CUSTOMER"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("name=\"phone\"")))
            .andExpect(content().string(containsString("name=\"dob\"")))
            .andExpect(content().string(containsString("ngày sinh sẽ khóa sau khi lưu")))
            .andExpect(content().string(not(containsString("name=\"currentPassword\""))));
    }
    @Test void completedGoogleDobIsReadonly() throws Exception {
        when(users.viewProfile(7L)).thenReturn(new ProfileView(7L,"google@example.com","Nguyễn An",null,
                java.time.LocalDate.of(2000,1,1),"OTHER",null,true,false,false));
        mvc.perform(get("/profile").with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("01/01/2000")))
                .andExpect(content().string(not(containsString("name=\"dob\""))));
    }
    @Test void todayAndFutureDobAreRejectedBeforeService() throws Exception {
        for (var dob : List.of(java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(1))) {
            mvc.perform(post("/profile").with(user("customer").roles("CUSTOMER")).with(csrf())
                    .param("fullName","Nguyễn An").param("gender","OTHER").param("dob",dob.toString()))
                    .andExpect(redirectedUrl("/profile"))
                    .andExpect(flash().attribute("errorMessage","Ngày sinh phải là ngày trong quá khứ."));
        }
        verify(users,never()).updateProfile(any(),any());
    }
    @Test void guestAndStaffCannotOpenCustomerProfile() throws Exception {
        mvc.perform(get("/profile")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/profile").with(user("staff").roles("SALE_STAFF"))).andExpect(redirectedUrl("/error/403"));
        verifyNoInteractions(users,identity);
    }
    @Test void everyMutationWithoutCsrfRejectedBeforeService() throws Exception {
        for(String route:List.of("/profile","/profile/password","/profile/avatar","/profile/addresses",
                "/profile/addresses/9/default","/profile/addresses/9/delete"))
            mvc.perform(post(route).with(user("customer").roles("CUSTOMER"))).andExpect(status().isForbidden());
        verifyNoInteractions(users,addresses,identity);
    }
    @Test void ownerIdAlwaysComesFromPrincipalNotForm() throws Exception {
        mvc.perform(post("/profile").with(user("customer").roles("CUSTOMER")).with(csrf())
            .param("fullName","Nguyễn An").param("gender","MALE").param("userId","999"))
            .andExpect(redirectedUrl("/profile")).andExpect(flash().attributeExists("successMessage"));
        verify(users).updateProfile(eq(7L),any());
        mvc.perform(post("/profile/addresses/9/delete").with(user("customer").roles("CUSTOMER")).with(csrf()).param("userId","999"))
            .andExpect(redirectedUrl("/profile/addresses"));verify(addresses).delete(7L,9L);
    }
    @Test void invalidPasswordIsNotStoredInFlash() throws Exception {
        mvc.perform(post("/profile/password").with(user("customer").roles("CUSTOMER")).with(csrf())
            .param("currentPassword","secret").param("newPassword","weak").param("confirmPassword","weak"))
            .andExpect(redirectedUrl("/profile")).andExpect(flash().attributeExists("errorMessage"))
            .andExpect(flash().attributeCount(1));verify(users,never()).changePassword(any(),any());
    }
    @Test void foreignAvatarNotReturned() throws Exception {
        mvc.perform(get("/profile/avatar/00000000-0000-0000-0000-000000000000.png")
            .with(user("customer").roles("CUSTOMER"))).andExpect(status().isNotFound());verifyNoInteractions(avatars);
    }
    @Test void editAddressIsLimitedToCurrentOwnersList() throws Exception {
        mvc.perform(get("/profile/addresses?edit=99").with(user("customer").roles("CUSTOMER")))
            .andExpect(redirectedUrl("/profile/addresses")).andExpect(flash().attributeExists("errorMessage"));
    }
    @Test void successfulPasswordChangeInvalidatesSession() throws Exception {
        var session=new org.springframework.mock.web.MockHttpSession();
        mvc.perform(post("/profile/password").session(session).with(user("customer").roles("CUSTOMER")).with(csrf())
            .param("currentPassword","OldPass1!").param("newPassword","NewPass1!").param("confirmPassword","NewPass1!"))
            .andExpect(redirectedUrl("/auth/login?passwordChanged")).andExpect(cookie().maxAge("JSESSIONID",0));
        org.junit.jupiter.api.Assertions.assertTrue(session.isInvalid());verify(users).changePassword(eq(7L),any());
    }
    @Test void inactivePrincipalIsLoggedOutInsteadOfRedirectLoop() throws Exception {
        when(identity.resolve(any())).thenThrow(new com.ddagtech.aureliabooks.exception.AppException(
                com.ddagtech.aureliabooks.constant.ErrorCode.ACCOUNT_LOCKED));
        var session=new org.springframework.mock.web.MockHttpSession();
        mvc.perform(get("/profile").session(session).with(user("customer").roles("CUSTOMER")))
                .andExpect(redirectedUrl("/auth/login"));
        org.junit.jupiter.api.Assertions.assertTrue(session.isInvalid());
    }
    @Test void navbarReadsLatestAvatarInsteadOfSessionSnapshot() throws Exception {
        var role=new com.ddagtech.aureliabooks.entity.Role();role.setRoleName("ROLE_CUSTOMER");
        var account=com.ddagtech.aureliabooks.entity.User.builder().id(7L).isActive(true).role(role)
                .email("an@example.com").fullName("Tên cũ").avatarUrl("/profile/avatar/old.png").build();
        var principal=new com.ddagtech.aureliabooks.security.CustomUserDetails(account);
        when(navbarUsers.findById(7L)).thenReturn(java.util.Optional.of(account));
        account.setAvatarUrl("/profile/avatar/new.png");account.setFullName("Nguyễn An mới");
        var rendered=mvc.perform(get("/profile").with(user(principal))).andExpect(status().isOk())
                .andExpect(content().string(containsString("src=\"/profile/avatar/new.png\"")))
                .andExpect(content().string(containsString("Nguyễn An mới")))
                .andExpect(content().string(not(containsString("src=\"/profile/avatar/old.png\"")))).andReturn();
        String directory=System.getProperty("uc08.capture.dir");
        if(directory!=null) {
            var dir=java.nio.file.Path.of(directory);java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("profile-with-avatar.html"),rendered.getResponse().getContentAsString());
        }
    }
    @Test void navbarResolvesGoogleAvatarBySubject() throws Exception {
        var account=com.ddagtech.aureliabooks.entity.User.builder().id(7L).isActive(true)
                .fullName("Nguyễn An Google").avatarUrl("/profile/avatar/google.png").build();
        when(navbarUsers.findByGoogleIdentity(com.ddagtech.aureliabooks.entity.User.AuthProvider.GOOGLE,"nav-subject"))
                .thenReturn(java.util.Optional.of(account));
        mvc.perform(get("/profile").with(oidcLogin().idToken(t->t.subject("nav-subject"))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("src=\"/profile/avatar/google.png\"")));
        verify(navbarUsers).findByGoogleIdentity(com.ddagtech.aureliabooks.entity.User.AuthProvider.GOOGLE,"nav-subject");
    }
}
