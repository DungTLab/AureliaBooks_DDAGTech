package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.response.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.exception.IllegalStateTransitionException;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.*;
import com.ddagtech.aureliabooks.service.OrderStateMachineService;
import com.ddagtech.aureliabooks.service.impl.AccountPresentationServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.domain.*;
import org.springframework.dao.CannotAcquireLockException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StaffOrderController.class)
@Import({SecurityConfig.class, CustomAccessDeniedHandler.class, AccountPresentationServiceImpl.class})
class StaffOrderControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OrderStateMachineService orders;
    @MockitoBean StaffIdentity identity;
    @MockitoBean UserRepository users;

    @BeforeEach void setup() { when(identity.resolve(any())).thenReturn(7L); }

    @Test void queueRendersFiltersAndKeepsThemWhenPaging() throws Exception {
        var row = new StaffOrderQueueView(12L, "code", "Người nhận", LocalDateTime.now(),
                Order.OrderStatus.CONFIRMED, Order.PaymentMethod.COD, Order.PaymentStatus.UNPAID, BigDecimal.TEN, "Khách hàng", "buyer@example.test");
        when(orders.getOrderQueue(any(), any(), eq(7L))).thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 21));
        mvc.perform(get("/staff/orders").with(user("staff").roles("SALE_STAFF"))
                        .param("status", "CONFIRMED").param("paymentStatus", "UNPAID").param("fromDate", "2026-10-20"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Người nhận")))
                .andExpect(content().string(containsString("page=1&amp;status=CONFIRMED&amp;paymentStatus=UNPAID")))
                .andExpect(content().string(containsString("fromDate=2026-10-20")));
    }

    @Test void returnedPaidOrderShowsRefundReminder() throws Exception {
        var row = new StaffOrderQueueView(12L, "returned-code", "Người nhận", LocalDateTime.now(),
                Order.OrderStatus.RETURNED, Order.PaymentMethod.COD, Order.PaymentStatus.PAID,
                BigDecimal.TEN, "Khách hàng", "buyer@example.test");
        when(orders.getOrderQueue(any(), any(), eq(7L))).thenReturn(new PageImpl<>(List.of(row)));
        mvc.perform(get("/staff/orders").with(user("staff").roles("SALE_STAFF")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã trả hàng")))
                .andExpect(content().string(containsString("Cần hoàn tiền")));
        org.junit.jupiter.api.Assertions.assertFalse(new StaffOrderQueueView(12L, "returned-code", "Người nhận", LocalDateTime.now(),
                Order.OrderStatus.RETURNED, Order.PaymentMethod.COD, Order.PaymentStatus.REFUNDED,
                BigDecimal.TEN, "Khách hàng", "buyer@example.test").needsRefund());
    }

    @Test void pendingRefundFilterBindsAndSurvivesPagination() throws Exception {
        when(orders.getOrderQueue(any(), any(), eq(7L))).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 21));
        mvc.perform(get("/staff/orders").with(user("staff").roles("SALE_STAFF"))
                        .param("paymentStatus", "REFUND_PENDING"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chưa hoàn tiền")))
                .andExpect(content().string(containsString("paymentStatus=REFUND_PENDING")));
        verify(orders).getOrderQueue(argThat(filter -> filter.paymentStatus()
                == com.ddagtech.aureliabooks.dto.request.OrderQueueFilter.PaymentFilter.REFUND_PENDING), any(), eq(7L));
    }

    @Test void checklistShowsRecipientSnapshotsAndCsrfProtectedForms() throws Exception {
        var order = new OrderConfirmationView("code", LocalDateTime.now(), "Người nhận cũ", "0912345678", "Địa chỉ lúc đặt",
                Order.OrderStatus.CONFIRMED, Order.PaymentMethod.COD, Order.PaymentStatus.UNPAID,
                List.of(new OrderConfirmationView.Item(2L, "Sách snapshot", 3, BigDecimal.TEN, BigDecimal.valueOf(30))),
                BigDecimal.valueOf(30), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(30));
        when(orders.getPackagingChecklist(12L, 7L)).thenReturn(new PackagingChecklistDTO(12L, order,
                null, null, List.of(), List.of(Order.OrderStatus.SHIPPING, Order.OrderStatus.CANCELLED),
                "Khách hàng", "buyer@example.test", List.of(), false, false));
        mvc.perform(get("/staff/orders/12").with(user("staff").roles("SALE_STAFF")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Người nhận cũ")))
                .andExpect(content().string(containsString("Địa chỉ lúc đặt")))
                .andExpect(content().string(containsString("Sách snapshot")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test void customerCannotReadQueue() throws Exception {
        mvc.perform(get("/staff/orders").with(user("customer").roles("CUSTOMER"))
                .header("X-Requested-With", "XMLHttpRequest")).andExpect(status().isForbidden());
        verifyNoInteractions(orders);
    }

    @Test void mutationRequiresCsrf() throws Exception {
        mvc.perform(post("/staff/orders/12/transition").with(user("staff").roles("SALE_STAFF"))
                .param("targetStatus", "CONFIRMED")).andExpect(status().isForbidden());
        verifyNoInteractions(orders);
    }

    @Test void transitionUsesStaffIdentityInsteadOfForgedId() throws Exception {
        mvc.perform(post("/staff/orders/12/transition").with(user("staff").roles("SALE_STAFF")).with(csrf())
                .param("staffId", "999").param("targetStatus", "CONFIRMED"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/staff/orders/12"));
        verify(orders).transitionStatus(eq(12L), argThat(r -> r.targetStatus() == Order.OrderStatus.CONFIRMED), eq(7L));
    }

    @Test void illegalTransitionReturnsConflict() throws Exception {
        when(orders.getPackagingChecklist(12L, 7L)).thenReturn(latestChecklist());
        doThrow(new IllegalStateTransitionException(Order.OrderStatus.PENDING_CONFIRMATION, Order.OrderStatus.DELIVERED))
                .when(orders).transitionStatus(eq(12L), any(), eq(7L));
        mvc.perform(post("/staff/orders/12/transition").with(user("staff").roles("SALE_STAFF")).with(csrf())
                .param("targetStatus", "DELIVERED")).andExpect(status().isConflict())
                .andExpect(view().name("staff/packaging-checklist"));
    }

    private PackagingChecklistDTO latestChecklist() {
        var confirmation = new OrderConfirmationView("code", LocalDateTime.now(), "Người nhận", "0912345678", "Địa chỉ",
                Order.OrderStatus.CONFIRMED, Order.PaymentMethod.COD, Order.PaymentStatus.UNPAID,
                List.of(), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN);
        return new PackagingChecklistDTO(12L, confirmation, null, null, List.of(), List.of(Order.OrderStatus.SHIPPING),
                "Khách hàng", "buyer@example.test", List.of(), false, false);
    }

    @Test void lockFailureReturnsConflictAndReloadMessage() throws Exception {
        doThrow(new CannotAcquireLockException("locked")).when(orders).transitionStatus(eq(12L), any(), eq(7L));
        mvc.perform(post("/staff/orders/12/transition").with(user("staff").roles("SALE_STAFF")).with(csrf())
                .param("targetStatus", "CONFIRMED"))
                .andExpect(status().isConflict()).andExpect(content().string(containsString("Vui lòng tải lại đơn")));
    }

    @Test void missingTargetReturnsBadRequest() throws Exception {
        mvc.perform(post("/staff/orders/12/transition").with(user("staff").roles("SALE_STAFF")).with(csrf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orders);
    }

    @Test void invalidDateDoesNotSilentlyIgnoreFilter() throws Exception {
        mvc.perform(get("/staff/orders").with(user("staff").roles("SALE_STAFF")).param("fromDate", "not-a-date"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orders);
    }

    @Test void refundFormDisplaysEvidenceAndHistory() throws Exception {
        var order = new OrderConfirmationView("paid-code", LocalDateTime.now(), "Người nhận", "0912345678", "Địa chỉ",
                Order.OrderStatus.CANCELLED, Order.PaymentMethod.VNPAY, Order.PaymentStatus.PAID,
                List.of(), new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100000"));
        when(orders.getPackagingChecklist(12L, 7L)).thenReturn(new PackagingChecklistDTO(12L, order,
                null, "Khách hủy", List.of(), List.of(), "Khách hàng", "buyer@example.test",
                List.of(new OrderActivityView(LocalDateTime.now(), "Staff A", "ORDER_STATUS_CHANGED", "PAID → PAID; Khách hủy")), true, true));
        mvc.perform(get("/staff/orders/12").with(user("staff").roles("SALE_STAFF")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Cần hoàn tiền")))
                .andExpect(content().string(containsString("name=\"completedDate\"")))
                .andExpect(content().string(containsString("<select id=\"packaging-checklist-control-1\" name=\"method\"")))
                .andExpect(content().string(containsString("<option value=\"Tiền mặt\" selected>Tiền mặt</option>")))
                .andExpect(content().string(containsString("<option value=\"Chuyển khoản\">Chuyển khoản</option>")))
                .andExpect(content().string(containsString("value=\"" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")) + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("name=\"reference\""))))
                .andExpect(content().string(containsString("name=\"externallyCompleted\"")))
                .andExpect(content().string(containsString("Staff A")))
                .andExpect(content().string(containsString("Phí giao hàng")));
    }

    @Test void shippingCodFormRequiresCollectionConfirmation() throws Exception {
        var order = new OrderConfirmationView("cod-code", LocalDateTime.now(), "Người nhận", "0912345678", "Địa chỉ",
                Order.OrderStatus.SHIPPING, Order.PaymentMethod.COD, Order.PaymentStatus.UNPAID,
                List.of(), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN);
        when(orders.getPackagingChecklist(12L, 7L)).thenReturn(new PackagingChecklistDTO(12L, order, null, null,
                List.of(), List.of(Order.OrderStatus.DELIVERED), "Buyer", "buyer@example.test", List.of(), false, false));
        mvc.perform(get("/staff/orders/12").with(user("staff").roles("SALE_STAFF")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"codCollected\"")))
                .andExpect(content().string(containsString("Tôi xác nhận giao thành công và đã thu đủ tiền COD.")));
    }

    @Test void refundUsesActorAndRequiresCsrf() throws Exception {
        mvc.perform(post("/staff/orders/12/refund").with(user("staff").roles("SALE_STAFF")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/staff/orders/12/refund").with(user("staff").roles("SALE_STAFF")).with(csrf())
                .param("amount", "100000").param("method", "Tiền mặt").param("completedDate", java.time.LocalDate.now().toString())
                .param("supportingInfo", "Biên nhận hoàn tiền").param("externallyCompleted", "true").param("staffId", "999"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/staff/orders/12"));
        verify(orders).confirmExternalRefund(eq(12L), any(), eq(7L));
    }

    @Test void managerCannotPostRefund() throws Exception {
        mvc.perform(post("/staff/orders/12/refund").with(user("manager").roles("MANAGER")).with(csrf())
                .header("X-Requested-With", "XMLHttpRequest")).andExpect(status().isForbidden());
        verify(orders, never()).confirmExternalRefund(any(), any(), any());
    }

    @Test void failedRetrievalIsAnErrorAndNotAnEmptyQueue() throws Exception {
        when(orders.getOrderQueue(any(), any(), any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
        mvc.perform(get("/staff/orders").with(user("staff").roles("SALE_STAFF")))
                .andExpect(status().isServiceUnavailable()).andExpect(view().name("staff/order-error"));
    }

    @Test void returnRestockSucceedsForStaffAndRedirects() throws Exception {
        mvc.perform(post("/staff/orders/12/return-restock").with(user("staff").roles("SALE_STAFF")).with(csrf())
                        .param("fullReturnVerified", "true")
                        .param("returnReason", "Hàng giao không thành công hoàn kho")
                        .param("inspectionNote", "Hàng nguyên vẹn"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/staff/orders/12"));
        verify(orders).confirmReturnRestock(eq(12L), any(), eq(7L));
    }

    @Test void returnRestockForbiddenForManager() throws Exception {
        mvc.perform(post("/staff/orders/12/return-restock").with(user("manager").roles("MANAGER")).with(csrf())
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("fullReturnVerified", "true")
                        .param("returnReason", "Hàng hoàn"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orders);
    }

    @Test void returnRestockValidationFailureRendersLatestChecklist() throws Exception {
        when(orders.getPackagingChecklist(12L, 7L)).thenReturn(latestChecklist());
        mvc.perform(post("/staff/orders/12/return-restock").with(user("staff").roles("SALE_STAFF")).with(csrf())
                        .param("fullReturnVerified", "false")
                        .param("returnReason", ""))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("staff/packaging-checklist"));
        verify(orders, never()).confirmReturnRestock(any(), any(), any());
    }
}
