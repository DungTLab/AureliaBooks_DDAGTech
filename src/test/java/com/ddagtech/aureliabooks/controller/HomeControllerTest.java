package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@WebMvcTest(controllers = HomeController.class)
@Import(SecurityConfig.class)
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET / should return 200 OK and render index template with title")
    void testHomePageReturns200AndRendersIndex() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attributeExists("title"));
    }

    @Test
    @DisplayName("GET /home should return 200 OK and render index template")
    void testHomePathReturns200AndRendersIndex() throws Exception {
        mockMvc.perform(get("/home"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attributeExists("title"));
    }
    @Test
    @DisplayName("Homepage includes mobile navigation behavior inside the rendered fragment")
    void rendersMobileNavigationScript() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"mobileMenuBtn\"")))
                .andExpect(content().string(containsString("const mobileMenuBtn")))
                .andExpect(content().string(containsString("mobileMenuBtn.addEventListener")));
    }

    @Test
    @DisplayName("Authenticated homepage includes dropdown behavior and toast rendering")
    void rendersDropdownAndToastScript() throws Exception {
        mockMvc.perform(get("/").with(user("layout-review").roles("CUSTOMER"))
                        .flashAttr("successMessage", "LAYOUT_TOAST_TEST"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"userMenuBtn\"")))
                .andExpect(content().string(containsString("userMenuBtn.addEventListener")))
                .andExpect(content().string(containsString("LAYOUT_TOAST_TEST")))
                .andExpect(content().string(containsString("function closeToast")));
    }
}
