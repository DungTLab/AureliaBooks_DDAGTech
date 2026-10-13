package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.config.SecurityConfig;
import com.ddagtech.aureliabooks.dto.request.GoodsReceiptForm;
import com.ddagtech.aureliabooks.dto.response.GoodsReceiptDetailDto;
import com.ddagtech.aureliabooks.dto.response.StockMetricSummary;
import com.ddagtech.aureliabooks.entity.GoodsReceipt;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.RoleRepository;
import com.ddagtech.aureliabooks.repository.SupplierRepository;
import com.ddagtech.aureliabooks.security.CustomUserDetails;
import com.ddagtech.aureliabooks.service.GoodsReceiptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminReceiptController.class)
@Import(SecurityConfig.class)
@MockitoBean(types = RoleRepository.class)
@DisplayName("UC21, UC22, UC23: Admin Receipt & Stock Alert Controller WebMvc Tests")
class AdminReceiptControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private GoodsReceiptService goodsReceiptService;

    @MockitoBean
    private SupplierRepository supplierRepository;

    @MockitoBean
    private ProductRepository productRepository;

    @MockitoBean
    private CategoryRepository categoryRepository;

    private CustomUserDetails mockUser(Long id, String email, String role) {
        com.ddagtech.aureliabooks.entity.Role r = new com.ddagtech.aureliabooks.entity.Role();
        r.setRoleName("ROLE_" + role);
        com.ddagtech.aureliabooks.entity.User u = new com.ddagtech.aureliabooks.entity.User();
        u.setId(id);
        u.setEmail(email);
        u.setFullName("Tester " + role);
        u.setRole(r);
        u.setIsActive(true);
        return new CustomUserDetails(u);
    }

    // =========================================================================
    // Security & Access Control Tests
    // =========================================================================

    @Test
    @DisplayName("GET /staff/receipts/new: SALE_STAFF truy cập được, trả về 200 OK")
    void draftForm_saleStaff_accessGranted() throws Exception {
        when(supplierRepository.findByIsActiveTrueOrderByNameAsc()).thenReturn(Collections.emptyList());
        when(productRepository.findByIsActiveTrueOrderByTitleAsc()).thenReturn(Collections.emptyList());

        mvc.perform(get("/staff/receipts/new").with(user(mockUser(10L, "staff@aureliabooks.com", "SALE_STAFF"))))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/receipts/form"));
    }

    @Test
    @DisplayName("GET /staff/receipts/new: CUSTOMER bị chặn, trả về 403 Forbidden")
    void draftForm_customer_accessDenied() throws Exception {
        mvc.perform(get("/staff/receipts/new").with(user(mockUser(99L, "cust@aureliabooks.com", "CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /manager/receipts: MANAGER truy cập được, trả về 200 OK")
    void listReceipts_manager_accessGranted() throws Exception {
        when(goodsReceiptService.getReceipts(any(), any(), any())).thenReturn(Page.empty());
        when(supplierRepository.findByIsActiveTrueOrderByNameAsc()).thenReturn(Collections.emptyList());

        mvc.perform(get("/manager/receipts").with(user(mockUser(20L, "manager@aureliabooks.com", "MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/receipts/list"));
    }

    // =========================================================================
    // UC22: Create / Update / Delete Draft Actions
    // =========================================================================

    @Test
    @DisplayName("POST /staff/receipts: Lập phiếu DRAFT thành công và redirect")
    void createDraft_success_redirects() throws Exception {
        when(goodsReceiptService.createDraft(eq(10L), any(GoodsReceiptForm.class))).thenReturn(101L);

        mvc.perform(post("/staff/receipts")
                        .with(user(mockUser(10L, "staff@aureliabooks.com", "SALE_STAFF")))
                        .with(csrf())
                        .param("supplierId", "1")
                        .param("note", "Nhập hàng quý 4")
                        .param("items[0].productId", "100")
                        .param("items[0].receivedQuantity", "50")
                        .param("items[0].unitCost", "45000.00"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));
    }

    @Test
    @DisplayName("POST /manager/receipts/{id}/finalize: Manager duyệt phiếu thành công, tăng tồn kho và redirect")
    void finalizeReceipt_success_redirects() throws Exception {
        mvc.perform(post("/manager/receipts/101/finalize")
                        .with(user(mockUser(20L, "manager@aureliabooks.com", "MANAGER")))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/receipts"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    @Test
    @DisplayName("GET /api/receipts/{id}: Trả về chi tiết phiếu JSON cho modal xem chi tiết")
    void getReceiptApi_success_returnsJson() throws Exception {
        GoodsReceiptDetailDto detail = new GoodsReceiptDetailDto(
                101L,
                "GR-20261011-0001",
                1L,
                "NXB Trẻ",
                "0901234567",
                "nxb@tre.com",
                10L,
                "Đức Anh",
                GoodsReceipt.Status.RECEIVED,
                new BigDecimal("2250000.00"),
                "Ghi chú",
                LocalDateTime.now(),
                20L,
                "Lê Tiến Dũng",
                LocalDateTime.now(),
                LocalDateTime.now(),
                List.of()
        );

        when(goodsReceiptService.getReceiptDetail(101L)).thenReturn(detail);

        mvc.perform(get("/api/receipts/101")
                        .with(user(mockUser(20L, "manager@aureliabooks.com", "MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.receiptCode").value("GR-20261011-0001"))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    // =========================================================================
    // UC21: Stock Alerts Monitoring
    // =========================================================================

    @Test
    @DisplayName("GET /manager/stock/alerts: Hiển thị trang cảnh báo tồn kho với metrics và bảng sản phẩm")
    void stockAlerts_manager_rendersView() throws Exception {
        when(goodsReceiptService.getStockMetrics()).thenReturn(new StockMetricSummary(100, 15, 25, 60));
        when(goodsReceiptService.getStockAlerts(any(), any(), any(), any(), any())).thenReturn(Page.empty());
        when(categoryRepository.findByIsActiveTrueOrderByNameAsc()).thenReturn(Collections.emptyList());

        mvc.perform(get("/manager/stock/alerts").with(user(mockUser(20L, "manager@aureliabooks.com", "MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/stock/alerts"))
                .andExpect(model().attributeExists("metrics"))
                .andExpect(model().attributeExists("alertsPage"));
    }

    @Test
    @DisplayName("GET /staff/stock/alerts: Sale Staff cũng có quyền truy cập cảnh báo tồn kho (UC21)")
    void stockAlerts_saleStaff_accessGranted() throws Exception {
        when(goodsReceiptService.getStockMetrics()).thenReturn(new StockMetricSummary(100, 15, 25, 60));
        when(goodsReceiptService.getStockAlerts(any(), any(), any(), any(), any())).thenReturn(Page.empty());
        when(categoryRepository.findByIsActiveTrueOrderByNameAsc()).thenReturn(Collections.emptyList());

        mvc.perform(get("/staff/stock/alerts").with(user(mockUser(10L, "staff@aureliabooks.com", "SALE_STAFF"))))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/stock/alerts"));
    }

    @Test
    @DisplayName("POST /api/products/quick-create: Tạo nhanh sản phẩm thành công trả về 200 OK và JSON")
    void quickCreateProduct_success() throws Exception {
        com.ddagtech.aureliabooks.dto.response.QuickProductResponse res =
                new com.ddagtech.aureliabooks.dto.response.QuickProductResponse(
                        99L, "Sách Mới Thêm Nhanh", "BK-99",
                        new BigDecimal("90000.00"), new BigDecimal("60000.00"),
                        "BOOK", false, 15
                );
        when(goodsReceiptService.quickCreateProduct(any())).thenReturn(res);

        String json = """
                {
                    "title": "Sách Mới Thêm Nhanh",
                    "productType": "BOOK",
                    "isTextbook": false,
                    "price": 90000.00,
                    "originalCost": 60000.00,
                    "initialQuantity": 15
                }
                """;

        mvc.perform(post("/api/products/quick-create")
                        .with(user(mockUser(10L, "staff@aureliabooks.com", "SALE_STAFF")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.title").value("Sách Mới Thêm Nhanh"))
                .andExpect(jsonPath("$.barcode").value("BK-99"))
                .andExpect(jsonPath("$.initialQuantity").value(15));
    }

    @Test
    @DisplayName("POST /api/products/quick-create: Validation error trả về 400 Bad Request")
    void quickCreateProduct_validationError() throws Exception {
        String invalidJson = """
                {
                    "title": "",
                    "productType": "BOOK",
                    "price": -100.00
                }
                """;

        mvc.perform(post("/api/products/quick-create")
                        .with(user(mockUser(10L, "staff@aureliabooks.com", "SALE_STAFF")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }
}
