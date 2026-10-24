package com.ddagtech.aureliabooks.controller;

import static org.junit.jupiter.api.Assertions.*;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@WebMvcTest(GoogleProfileController.class)
@Import(SecurityConfig.class)
class GoogleProfileRoutesTest {

    @Autowired
    RequestMappingHandlerMapping mappings;

    @MockitoBean
    CustomerIdentity identity;

    @Test
    void manualGoogleLinkIsRemovedAndGoogleReauthenticationRemains() {
        var paths = mappings
            .getHandlerMethods()
            .keySet()
            .stream()
            .flatMap(mapping -> mapping.getPatternValues().stream())
            .toList();
        assertFalse(paths.contains("/profile/google/link"));
        assertTrue(paths.contains("/profile/google/reauth"));
    }
}
