package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PolicyController.class)
@Import(SecurityConfig.class)
class PolicyControllerTest {

    @Autowired private MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"terms", "returns", "shipping", "payment", "faq"})
    void policyIsPublicAndSelectedExplicitly(String page) throws Exception {
        mvc.perform(get("/policies/" + page)).andExpect(status().isOk())
                .andExpect(view().name("policies/view")).andExpect(model().attribute("policyPage", page));
    }

    @Test
    void unknownPolicyPreservesNotFoundStatus() throws Exception {
        mvc.perform(get("/policies/not-a-policy")).andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }
}
