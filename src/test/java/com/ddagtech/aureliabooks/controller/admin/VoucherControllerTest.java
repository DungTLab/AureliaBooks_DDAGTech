package com.ddagtech.aureliabooks.controller.admin;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ddagtech.aureliabooks.config.*;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.response.VoucherSummary;
import com.ddagtech.aureliabooks.entity.Voucher;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.security.StaffIdentity;
import com.ddagtech.aureliabooks.service.VoucherService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.*;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(VoucherController.class)
@Import({ SecurityConfig.class, PromotionConfiguration.class })
class VoucherControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    VoucherService vouchers;

    @MockitoBean
    StaffIdentity identity;

    @Test
    void guestMustSignIn() throws Exception {
        mvc.perform(get("/manager/vouchers")).andExpect(status().is3xxRedirection());
        verifyNoInteractions(vouchers);
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerCannotManage() throws Exception {
        mvc.perform(get("/manager/vouchers")).andExpect(status().isForbidden());
        verifyNoInteractions(vouchers);
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void formRendersSharedLayoutVietnameseAndCsrf() throws Exception {
        var result = mvc
            .perform(get("/manager/vouchers/new"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("name=\"_csrf\"")))
            .andExpect(content().string(containsString("data-form-validation")))
            .andExpect(content().string(containsString("/js/form-validation.js")))
            .andExpect(content().string(containsString("Tạo mã giảm giá")))
            .andReturn();
        capture("voucher-create.html", result.getResponse().getContentAsString());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void listRendersRedemptionCountsAndStatusForms() throws Exception {
        when(identity.resolve(any())).thenReturn(7L);
        var v = new VoucherSummary(
            1L,
            "SALE10",
            "Giảm 10%",
            Voucher.Scope.PRODUCT,
            Voucher.Type.PERCENTAGE,
            BigDecimal.TEN,
            null,
            new BigDecimal("50000"),
            BigDecimal.ZERO,
            LocalDateTime.now(),
            LocalDateTime.now().plusDays(1),
            9,
            true,
            1
        );
        when(vouchers.list(eq(7L), any(), any(), any())).thenReturn(new PageImpl<>(List.of(v)));
        var result = mvc
            .perform(get("/manager/vouchers"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("SALE10")))
            .andExpect(content().string(containsString("lượt đang ghi nhận")))
            .andReturn();
        capture("voucher-list.html", result.getResponse().getContentAsString());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void validCreateUsesAuthenticatedActor() throws Exception {
        when(identity.resolve(any())).thenReturn(7L);
        mvc.perform(valid().with(csrf())).andExpect(redirectedUrl("/manager/vouchers"));
        verify(vouchers).create(eq(7L), argThat(f -> f.code().equals("SALE10")), anyString());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void missingCsrfNeverCreatesVoucher() throws Exception {
        mvc.perform(valid()).andExpect(status().isForbidden());
        verifyNoInteractions(vouchers);
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void invalidPercentageShowsValidationFeedback() throws Exception {
        mvc.perform(valid("101").with(csrf()))
            .andExpect(status().isOk())
            .andExpect(model().attributeHasErrors("voucherForm"));
        verifyNoInteractions(vouchers);
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void duplicateKeepsFormAndShowsLocalizedError() throws Exception {
        when(identity.resolve(any())).thenReturn(7L);
        when(vouchers.create(anyLong(), any(), any())).thenThrow(new AppException(ErrorCode.VOUCHER_CODE_EXISTED));
        mvc.perform(valid().with(csrf()))
            .andExpect(status().isOk())
            .andExpect(model().attributeHasErrors("voucherForm"))
            .andExpect(content().string(containsString("Mã giảm giá này đã tồn tại")));
    }

    private MockHttpServletRequestBuilder valid() {
        return valid("10");
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void clientButtonCannotBypassRequiredFieldsNumericPrecisionOrDateRange() throws Exception {
        for (String[] invalid : List.of(
            new String[] { "description", "   " },
            new String[] { "discountPercentage", "10.001" },
            new String[] { "usageLimit", "1.5" },
            new String[] { "maxDiscountAmount", "" },
            new String[] { "minOrderValue", "-1" },
            new String[] { "endDate", "2026-10-17T12:00" }
        )) {
            var request = valid()
                .with(csrf())
                .with(http -> {
                    http.setParameter(invalid[0], invalid[1]);
                    return http;
                });
            mvc.perform(request).andExpect(status().isOk()).andExpect(model().attributeHasErrors("voucherForm"));
        }
        verifyNoInteractions(vouchers);
    }

    private void capture(String name, String html) throws Exception {
        String directory = System.getProperty("voucher.ui.capture");
        if (directory != null) {
            var folder = java.nio.file.Path.of(directory);
            java.nio.file.Files.createDirectories(folder);
            java.nio.file.Files.writeString(folder.resolve(name), html);
        }
    }

    private MockHttpServletRequestBuilder valid(String percentage) {
        return post("/manager/vouchers")
            .param("code", "sale10")
            .param("description", "Giảm 10%")
            .param("scope", "PRODUCT")
            .param("type", "PERCENTAGE")
            .param("discountPercentage", percentage)
            .param("maxDiscountAmount", "50000")
            .param("minOrderValue", "0")
            .param("usageLimit", "10")
            .param("startDate", "2026-10-17T12:00")
            .param("endDate", "2026-10-20T12:00");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCannotCreateVoucher() throws Exception {
        mvc.perform(valid().with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(vouchers);
    }
}
