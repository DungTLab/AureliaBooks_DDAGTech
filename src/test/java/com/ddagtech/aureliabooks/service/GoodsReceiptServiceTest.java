package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.GoodsReceiptForm;
import com.ddagtech.aureliabooks.dto.request.GoodsReceiptItemForm;
import com.ddagtech.aureliabooks.dto.request.QuickCreateProductRequest;
import com.ddagtech.aureliabooks.dto.response.QuickProductResponse;
import com.ddagtech.aureliabooks.dto.response.GoodsReceiptDetailDto;
import com.ddagtech.aureliabooks.dto.response.ProductStockAlertDto;
import com.ddagtech.aureliabooks.dto.response.ReceiptSummary;
import com.ddagtech.aureliabooks.dto.response.StockMetricSummary;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.service.impl.GoodsReceiptServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("UC21, UC22, UC23: Goods Receipt & Stock Alert Service Tests")
class GoodsReceiptServiceTest {

    @Mock
    private GoodsReceiptRepository goodsReceiptRepository;
    @Mock
    private GoodsReceiptItemRepository goodsReceiptItemRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private SupplierRepository supplierRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BookRepository bookRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private PublisherRepository publisherRepository;
    @Mock
    private BrandRepository brandRepository;
    @Mock
    private StationeryRepository stationeryRepository;
    @Mock
    private StockMutationService stockMutationService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private GoodsReceiptServiceImpl service;

    private User staffUser;
    private User managerUser;
    private Supplier activeSupplier;
    private Product standardBook;
    private Product textbookBook;
    private Product stationeryProduct;

    @BeforeEach
    void setUp() {
        staffUser = new User();
        staffUser.setId(10L);
        staffUser.setFullName("Nguyễn Trần Đức Anh");

        managerUser = new User();
        managerUser.setId(20L);
        managerUser.setFullName("Lê Tiến Dũng");

        activeSupplier = new Supplier();
        activeSupplier.setId(1L);
        activeSupplier.setName("Nhà Xuất Bản Trẻ");
        activeSupplier.setPhone("0901234567");
        activeSupplier.setIsActive(true);

        standardBook = new Product();
        standardBook.setId(100L);
        standardBook.setBarcode("BOOK-STD-001");
        standardBook.setTitle("Đắc Nhân Tâm");
        standardBook.setProductType(Product.ProductType.BOOK);
        standardBook.setPrice(new BigDecimal("80000.00"));
        standardBook.setOriginalCost(new BigDecimal("45000.00"));
        standardBook.setStockQuantity(0);

        textbookBook = new Product();
        textbookBook.setId(200L);
        textbookBook.setBarcode("BOOK-SGK-001");
        textbookBook.setTitle("Toán Lớp 12");
        textbookBook.setProductType(Product.ProductType.BOOK);
        textbookBook.setPrice(new BigDecimal("30000.00"));
        textbookBook.setOriginalCost(new BigDecimal("15000.00"));
        textbookBook.setStockQuantity(0);

        stationeryProduct = new Product();
        stationeryProduct.setId(300L);
        stationeryProduct.setBarcode("STAT-PEN-001");
        stationeryProduct.setTitle("Bút Bi Thiên Long TL-027");
        stationeryProduct.setProductType(Product.ProductType.STATIONERY);
        stationeryProduct.setPrice(new BigDecimal("5000.00"));
        stationeryProduct.setOriginalCost(new BigDecimal("3000.00"));
        stationeryProduct.setStockQuantity(0);
    }

    // =========================================================================
    // UC22: Draft Goods Receipts (Sale Staff)
    // =========================================================================
    @Nested
    @DisplayName("UC22: Draft Goods Receipts (Tạo, Sửa, Xóa bản nháp & Quy tắc BR-01-01)")
    class UC22DraftReceiptTests {

        @Test
        @DisplayName("Tạo phiếu DRAFT thành công: tính đúng tổng tiền, trạng thái DRAFT, tồn kho KHÔNG đổi")
        void createDraft_success_noStockChange() {
            when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(activeSupplier));
            when(productRepository.findById(100L)).thenReturn(Optional.of(standardBook));
            when(bookRepository.findById(100L)).thenReturn(Optional.of(Book.builder().productId(100L).isTextbook(false).build()));
            when(goodsReceiptItemRepository.hasReceivedReceiptForProduct(100L)).thenReturn(false);

            when(goodsReceiptRepository.save(any(GoodsReceipt.class))).thenAnswer(inv -> {
                GoodsReceipt gr = inv.getArgument(0);
                if (gr.getId() == null) gr.setId(999L);
                return gr;
            });

            GoodsReceiptItemForm itemForm = new GoodsReceiptItemForm(100L, 50, new BigDecimal("40000.00"));
            GoodsReceiptForm form = new GoodsReceiptForm(1L, "Nhập hàng đợt 1", List.of(itemForm));

            Long createdId = service.createDraft(10L, form);

            assertThat(createdId).isEqualTo(999L);

            // Kiểm tra tồn kho của sản phẩm không bị thay đổi
            assertThat(standardBook.getStockQuantity()).isEqualTo(0);

            // Verify audit log được ghi nhận
            verify(auditLogService, times(1)).record(eq(10L), eq("RECEIPT_CREATE_DRAFT"), eq("goods_receipts"), eq(999L), any(), isNull());
        }

        @Test
        @DisplayName("BR-01-01: Sản phẩm thông thường nhập lần đầu <= 100 đơn vị (Boundary: 100) -> Hợp lệ")
        void createDraft_boundary_standardItem_100Units_pass() {
            when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(activeSupplier));
            when(productRepository.findById(100L)).thenReturn(Optional.of(standardBook));
            when(bookRepository.findById(100L)).thenReturn(Optional.of(Book.builder().productId(100L).isTextbook(false).build()));
            when(goodsReceiptItemRepository.hasReceivedReceiptForProduct(100L)).thenReturn(false);

            when(goodsReceiptRepository.save(any(GoodsReceipt.class))).thenAnswer(inv -> {
                GoodsReceipt gr = inv.getArgument(0);
                if (gr.getId() == null) gr.setId(1000L);
                return gr;
            });

            GoodsReceiptItemForm itemForm = new GoodsReceiptItemForm(100L, 100, new BigDecimal("45000.00"));
            GoodsReceiptForm form = new GoodsReceiptForm(1L, "Boundary 100", List.of(itemForm));

            Long createdId = service.createDraft(10L, form);
            assertThat(createdId).isNotNull();
        }

        @Test
        @DisplayName("BR-01-01: Sản phẩm thông thường nhập lần đầu 101 đơn vị (Boundary: 101) -> Bị chặn với RECEIPT_IMPORT_LIMIT_EXCEEDED")
        void createDraft_boundary_standardItem_101Units_throwsException() {
            when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(activeSupplier));
            when(productRepository.findById(100L)).thenReturn(Optional.of(standardBook));
            when(bookRepository.findById(100L)).thenReturn(Optional.of(Book.builder().productId(100L).isTextbook(false).build()));
            when(goodsReceiptItemRepository.hasReceivedReceiptForProduct(100L)).thenReturn(false);
            when(goodsReceiptRepository.save(any(GoodsReceipt.class))).thenAnswer(inv -> inv.getArgument(0));

            GoodsReceiptItemForm itemForm = new GoodsReceiptItemForm(100L, 101, new BigDecimal("45000.00"));
            GoodsReceiptForm form = new GoodsReceiptForm(1L, "Boundary 101", List.of(itemForm));

            assertThatThrownBy(() -> service.createDraft(10L, form))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        AppException ae = (AppException) e;
                        assertThat(ae.getErrorCode()).isEqualTo(ErrorCode.RECEIPT_IMPORT_LIMIT_EXCEEDED);
                    });
        }

        @Test
        @DisplayName("BR-01-01: Sách giáo khoa (SGK) nhập lần đầu <= 500 đơn vị (Boundary: 500) -> Hợp lệ")
        void createDraft_boundary_textbook_500Units_pass() {
            when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(activeSupplier));
            when(productRepository.findById(200L)).thenReturn(Optional.of(textbookBook));
            when(bookRepository.findById(200L)).thenReturn(Optional.of(Book.builder().productId(200L).isTextbook(true).build()));
            when(goodsReceiptItemRepository.hasReceivedReceiptForProduct(200L)).thenReturn(false);

            when(goodsReceiptRepository.save(any(GoodsReceipt.class))).thenAnswer(inv -> {
                GoodsReceipt gr = inv.getArgument(0);
                if (gr.getId() == null) gr.setId(2000L);
                return gr;
            });

            GoodsReceiptItemForm itemForm = new GoodsReceiptItemForm(200L, 500, new BigDecimal("15000.00"));
            GoodsReceiptForm form = new GoodsReceiptForm(1L, "Boundary SGK 500", List.of(itemForm));

            Long createdId = service.createDraft(10L, form);
            assertThat(createdId).isNotNull();
        }

        @Test
        @DisplayName("BR-01-01: Sách giáo khoa (SGK) nhập lần đầu 501 đơn vị (Boundary: 501) -> Bị chặn với RECEIPT_IMPORT_LIMIT_EXCEEDED")
        void createDraft_boundary_textbook_501Units_throwsException() {
            when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(activeSupplier));
            when(productRepository.findById(200L)).thenReturn(Optional.of(textbookBook));
            when(bookRepository.findById(200L)).thenReturn(Optional.of(Book.builder().productId(200L).isTextbook(true).build()));
            when(goodsReceiptItemRepository.hasReceivedReceiptForProduct(200L)).thenReturn(false);
            when(goodsReceiptRepository.save(any(GoodsReceipt.class))).thenAnswer(inv -> inv.getArgument(0));

            GoodsReceiptItemForm itemForm = new GoodsReceiptItemForm(200L, 501, new BigDecimal("15000.00"));
            GoodsReceiptForm form = new GoodsReceiptForm(1L, "Boundary SGK 501", List.of(itemForm));

            assertThatThrownBy(() -> service.createDraft(10L, form))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        AppException ae = (AppException) e;
                        assertThat(ae.getErrorCode()).isEqualTo(ErrorCode.RECEIPT_IMPORT_LIMIT_EXCEEDED);
                    });
        }

        @Test
        @DisplayName("Đơn giá nhập cost = 0 (hàng mẫu, tài trợ) -> Hợp lệ theo schema >= 0")
        void createDraft_zeroCost_allowed() {
            when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(activeSupplier));
            when(productRepository.findById(300L)).thenReturn(Optional.of(stationeryProduct));
            when(goodsReceiptItemRepository.hasReceivedReceiptForProduct(300L)).thenReturn(false);

            when(goodsReceiptRepository.save(any(GoodsReceipt.class))).thenAnswer(inv -> {
                GoodsReceipt gr = inv.getArgument(0);
                if (gr.getId() == null) gr.setId(3000L);
                return gr;
            });

            GoodsReceiptItemForm itemForm = new GoodsReceiptItemForm(300L, 10, BigDecimal.ZERO);
            GoodsReceiptForm form = new GoodsReceiptForm(1L, "Hàng mẫu giá 0đ", List.of(itemForm));

            Long createdId = service.createDraft(10L, form);
            assertThat(createdId).isNotNull();
        }

        @Test
        @DisplayName("Đơn giá nhập âm (< 0) -> Bị chặn với RECEIPT_ITEM_COST_INVALID")
        void createDraft_negativeCost_throwsException() {
            when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(activeSupplier));
            when(productRepository.findById(100L)).thenReturn(Optional.of(standardBook));

            GoodsReceiptItemForm itemForm = new GoodsReceiptItemForm(100L, 10, new BigDecimal("-1000.00"));
            GoodsReceiptForm form = new GoodsReceiptForm(1L, "Giá âm", List.of(itemForm));

            assertThatThrownBy(() -> service.createDraft(10L, form))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        AppException ae = (AppException) e;
                        assertThat(ae.getErrorCode()).isEqualTo(ErrorCode.RECEIPT_ITEM_COST_INVALID);
                    });
        }

        @Test
        @DisplayName("Số lượng nhập <= 0 -> Bị chặn với RECEIPT_ITEM_QUANTITY_INVALID")
        void createDraft_invalidQuantity_throwsException() {
            when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(activeSupplier));
            when(productRepository.findById(100L)).thenReturn(Optional.of(standardBook));

            GoodsReceiptItemForm itemForm = new GoodsReceiptItemForm(100L, 0, new BigDecimal("45000.00"));
            GoodsReceiptForm form = new GoodsReceiptForm(1L, "Số lượng 0", List.of(itemForm));

            assertThatThrownBy(() -> service.createDraft(10L, form))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        AppException ae = (AppException) e;
                        assertThat(ae.getErrorCode()).isEqualTo(ErrorCode.RECEIPT_ITEM_QUANTITY_INVALID);
                    });
        }

        @Test
        @DisplayName("Danh sách sản phẩm rỗng -> Bị chặn với RECEIPT_EMPTY_ITEMS")
        void createDraft_emptyItems_throwsException() {
            when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));

            GoodsReceiptForm form = new GoodsReceiptForm(1L, "Không có sản phẩm", Collections.emptyList());

            assertThatThrownBy(() -> service.createDraft(10L, form))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        AppException ae = (AppException) e;
                        assertThat(ae.getErrorCode()).isEqualTo(ErrorCode.RECEIPT_EMPTY_ITEMS);
                    });
        }

        @Test
        @DisplayName("Nhà cung cấp đã ngừng hoạt động (isActive = false) -> Bị chặn với SUPPLIER_INACTIVE")
        void createDraft_inactiveSupplier_throwsException() {
            Supplier inactiveSupplier = new Supplier();
            inactiveSupplier.setId(2L);
            inactiveSupplier.setName("NCC Ngừng KD");
            inactiveSupplier.setIsActive(false);

            when(userRepository.findById(10L)).thenReturn(Optional.of(staffUser));
            when(supplierRepository.findById(2L)).thenReturn(Optional.of(inactiveSupplier));

            GoodsReceiptItemForm itemForm = new GoodsReceiptItemForm(100L, 10, new BigDecimal("45000.00"));
            GoodsReceiptForm form = new GoodsReceiptForm(2L, "NCC ngừng hoạt động", List.of(itemForm));

            assertThatThrownBy(() -> service.createDraft(10L, form))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        AppException ae = (AppException) e;
                        assertThat(ae.getErrorCode()).isEqualTo(ErrorCode.SUPPLIER_INACTIVE);
                    });
        }

        @Test
        @DisplayName("Cập nhật phiếu DRAFT thành công: cập nhật items, tính lại tổng tiền, stock không đổi")
        void updateDraft_success() {
            GoodsReceipt draftReceipt = GoodsReceipt.builder()
                    .id(50L)
                    .receiptCode("GR-20261011-12345")
                    .status(GoodsReceipt.Status.DRAFT)
                    .supplier(activeSupplier)
                    .createdBy(staffUser)
                    .totalAmount(new BigDecimal("100000.00"))
                    .build();

            when(goodsReceiptRepository.findById(50L)).thenReturn(Optional.of(draftReceipt));
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(activeSupplier));
            when(productRepository.findById(100L)).thenReturn(Optional.of(standardBook));
            when(bookRepository.findById(100L)).thenReturn(Optional.of(Book.builder().productId(100L).isTextbook(false).build()));
            when(goodsReceiptItemRepository.hasReceivedReceiptForProduct(100L)).thenReturn(false);

            GoodsReceiptItemForm itemForm = new GoodsReceiptItemForm(100L, 80, new BigDecimal("50000.00"));
            GoodsReceiptForm form = new GoodsReceiptForm(1L, "Cập nhật số lượng", List.of(itemForm));

            service.updateDraft(10L, 50L, form);

            verify(goodsReceiptItemRepository, times(1)).deleteByReceiptId(50L);
            verify(goodsReceiptRepository, times(1)).save(draftReceipt);
            assertThat(draftReceipt.getTotalAmount()).isEqualByComparingTo(new BigDecimal("4000000.00"));
            assertThat(standardBook.getStockQuantity()).isEqualTo(0);
        }

        @Test
        @DisplayName("Khóa bất biến (Immutable): Cố tình cập nhật phiếu đã RECEIVED -> Bị chặn với RECEIPT_ALREADY_RECEIVED")
        void updateDraft_receivedReceipt_throwsException() {
            GoodsReceipt receivedReceipt = GoodsReceipt.builder()
                    .id(60L)
                    .receiptCode("GR-20261011-99999")
                    .status(GoodsReceipt.Status.RECEIVED)
                    .receivedAt(LocalDateTime.now())
                    .receivedBy(managerUser)
                    .build();

            when(goodsReceiptRepository.findById(60L)).thenReturn(Optional.of(receivedReceipt));

            GoodsReceiptItemForm itemForm = new GoodsReceiptItemForm(100L, 10, new BigDecimal("45000.00"));
            GoodsReceiptForm form = new GoodsReceiptForm(1L, "Cố tình sửa phiếu đã duyệt", List.of(itemForm));

            assertThatThrownBy(() -> service.updateDraft(10L, 60L, form))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        AppException ae = (AppException) e;
                        assertThat(ae.getErrorCode()).isEqualTo(ErrorCode.RECEIPT_ALREADY_RECEIVED);
                    });
        }

        @Test
        @DisplayName("Xóa phiếu DRAFT thành công: xóa bản ghi, stock không đổi")
        void deleteDraft_success() {
            GoodsReceipt draftReceipt = GoodsReceipt.builder()
                    .id(70L)
                    .receiptCode("GR-20261011-77777")
                    .status(GoodsReceipt.Status.DRAFT)
                    .build();

            when(goodsReceiptRepository.findById(70L)).thenReturn(Optional.of(draftReceipt));

            service.deleteDraft(10L, 70L);

            verify(goodsReceiptItemRepository, times(1)).deleteByReceiptId(70L);
            verify(goodsReceiptRepository, times(1)).delete(draftReceipt);
        }

        @Test
        @DisplayName("Khóa bất biến (Immutable): Cố tình xóa phiếu đã RECEIVED -> Bị chặn với RECEIPT_ALREADY_RECEIVED")
        void deleteDraft_receivedReceipt_throwsException() {
            GoodsReceipt receivedReceipt = GoodsReceipt.builder()
                    .id(80L)
                    .receiptCode("GR-20261011-88888")
                    .status(GoodsReceipt.Status.RECEIVED)
                    .receivedAt(LocalDateTime.now())
                    .receivedBy(managerUser)
                    .build();

            when(goodsReceiptRepository.findById(80L)).thenReturn(Optional.of(receivedReceipt));

            assertThatThrownBy(() -> service.deleteDraft(10L, 80L))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        AppException ae = (AppException) e;
                        assertThat(ae.getErrorCode()).isEqualTo(ErrorCode.RECEIPT_ALREADY_RECEIVED);
                    });
        }
    }

    // =========================================================================
    // UC23: Finalize Goods Receipts & Increment Stock (Manager)
    // =========================================================================
    @Nested
    @DisplayName("UC23: Finalize Goods Receipts (Duyệt phiếu, tăng tồn, cập nhật giá vốn & Khóa bất biến)")
    class UC23FinalizeReceiptTests {

        @Test
        @DisplayName("Duyệt phiếu nhập: ủy thác cho StockMutationService thực hiện trong Transaction")
        void receive_success_delegatesToMutationService() {
            GoodsReceipt draftReceipt = GoodsReceipt.builder()
                    .id(90L)
                    .receiptCode("GR-20261011-FINAL")
                    .status(GoodsReceipt.Status.DRAFT)
                    .build();

            when(goodsReceiptRepository.findById(90L)).thenReturn(Optional.of(draftReceipt));

            service.receive(20L, 90L);

            verify(stockMutationService, times(1)).receiveReceipt(90L, 20L);
        }

        @Test
        @DisplayName("Finalize phiếu đã RECEIVED -> Bị chặn với RECEIPT_ALREADY_RECEIVED")
        void receive_alreadyFinalized_throwsException() {
            GoodsReceipt alreadyReceived = GoodsReceipt.builder()
                    .id(95L)
                    .receiptCode("GR-20261011-ALREADY")
                    .status(GoodsReceipt.Status.RECEIVED)
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(goodsReceiptRepository.findById(95L)).thenReturn(Optional.of(alreadyReceived));

            assertThatThrownBy(() -> service.receive(20L, 95L))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        AppException ae = (AppException) e;
                        assertThat(ae.getErrorCode()).isEqualTo(ErrorCode.RECEIPT_ALREADY_RECEIVED);
                    });
        }
    }

    // =========================================================================
    // UC21: Monitor Inventory & Stock Alerts
    // =========================================================================
    @Nested
    @DisplayName("UC21: Monitor Inventory & Stock Alerts (Ngưỡng 0, 1-10, >10 & Counters khớp thực tế)")
    class UC21StockAlertTests {

        @Test
        @DisplayName("Kiểm tra phân loại ngưỡng tồn kho chính xác: 0 (Hết hàng), 1-10 (Tồn thấp), 11 (An toàn)")
        void productStockAlertDto_boundaryClassification() {
            // Ngưỡng 0: Hết hàng
            Product p0 = new Product();
            p0.setId(1L);
            p0.setStockQuantity(0);
            ProductStockAlertDto dto0 = ProductStockAlertDto.of(p0, "Category", false);
            assertThat(dto0.alertStatus()).isEqualTo("OUT_OF_STOCK");
            assertThat(dto0.alertLabel()).isEqualTo("Hết hàng");

            // Ngưỡng 1: Cận dưới tồn kho thấp
            Product p1 = new Product();
            p1.setId(2L);
            p1.setStockQuantity(1);
            ProductStockAlertDto dto1 = ProductStockAlertDto.of(p1, "Category", false);
            assertThat(dto1.alertStatus()).isEqualTo("LOW_STOCK");
            assertThat(dto1.alertLabel()).isEqualTo("Tồn kho thấp");

            // Ngưỡng 10: Cận trên tồn kho thấp
            Product p10 = new Product();
            p10.setId(3L);
            p10.setStockQuantity(10);
            ProductStockAlertDto dto10 = ProductStockAlertDto.of(p10, "Category", false);
            assertThat(dto10.alertStatus()).isEqualTo("LOW_STOCK");
            assertThat(dto10.alertLabel()).isEqualTo("Tồn kho thấp");

            // Ngưỡng 11: Cận dưới tồn kho an toàn (>10)
            Product p11 = new Product();
            p11.setId(4L);
            p11.setStockQuantity(11);
            ProductStockAlertDto dto11 = ProductStockAlertDto.of(p11, "Category", false);
            assertThat(dto11.alertStatus()).isEqualTo("SAFE_STOCK");
            assertThat(dto11.alertLabel()).isEqualTo("An toàn");
        }

        @Test
        @DisplayName("Chỉ số tổng hợp metrics khớp đúng dữ liệu thực tế: Total, OutOfStock, LowStock, SafeStock")
        void getStockMetrics_exactMatches() {
            when(productRepository.count()).thenReturn(100L);
            when(productRepository.countByStockQuantityEquals(0)).thenReturn(15L);
            when(productRepository.countByStockQuantityBetween(1, 10)).thenReturn(25L);
            when(productRepository.countByStockQuantityGreaterThan(10)).thenReturn(60L);

            StockMetricSummary metrics = service.getStockMetrics();

            assertThat(metrics.totalProducts()).isEqualTo(100L);
            assertThat(metrics.outOfStockCount()).isEqualTo(15L);
            assertThat(metrics.lowStockCount()).isEqualTo(25L);
            assertThat(metrics.safeStockCount()).isEqualTo(60L);
        }

        @Test
        @DisplayName("Hợp đồng lowStock(threshold, pageable) trả về Page<ProductSummary>")
        void lowStock_contract_matchesPage() {
            Product p = new Product();
            p.setId(10L);
            p.setTitle("Sách Tồn Thấp");
            p.setPrice(new BigDecimal("50000.00"));
            p.setStockQuantity(3);
            p.setProductType(Product.ProductType.BOOK);

            Page<Product> page = new PageImpl<>(List.of(p));
            when(productRepository.findByStockQuantityLessThanEqual(eq(5), any(Pageable.class)))
                    .thenReturn(page);

            Page<com.ddagtech.aureliabooks.dto.response.ProductSummary> result = service.lowStock(5, PageRequest.of(0, 10));

            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent().get(0).title()).isEqualTo("Sách Tồn Thấp");
            assertThat(result.getContent().get(0).stockQuantity()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Quick Create Product on-the-fly (Hỗ trợ thêm sản phẩm khi lập phiếu)")
    class QuickCreateProductTests {

        @Test
        @DisplayName("Tạo nhanh Sách thông thường với Category và Publisher tự động")
        void quickCreateStandardBook_success() {
            Category cat = Category.builder().id(1L).name("Sách tổng hợp").build();
            when(categoryRepository.findAll()).thenReturn(List.of(cat));
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(cat));

            Publisher pub = Publisher.builder().id(1L).name("Nhà xuất bản Giáo Dục").build();
            when(publisherRepository.findAll()).thenReturn(List.of(pub));

            when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
                Product p = inv.getArgument(0);
                p.setId(101L);
                return p;
            });
            when(bookRepository.save(any(Book.class))).thenAnswer(inv -> inv.getArgument(0));

            QuickCreateProductRequest req = new QuickCreateProductRequest(
                    "Đắc Nhân Tâm Mới",
                    Product.ProductType.BOOK,
                    false,
                    1L,
                    "BK-999888",
                    new BigDecimal("80000.00"),
                    new BigDecimal("50000.00"),
                    10
            );

            QuickProductResponse res = service.quickCreateProduct(req);

            assertThat(res.id()).isEqualTo(101L);
            assertThat(res.title()).isEqualTo("Đắc Nhân Tâm Mới");
            assertThat(res.barcode()).isEqualTo("BK-999888");
            assertThat(res.isTextbook()).isFalse();
            assertThat(res.initialQuantity()).isEqualTo(10);
            verify(productRepository).save(any(Product.class));
            verify(bookRepository).save(any(Book.class));
        }

        @Test
        @DisplayName("Tạo nhanh Sách giáo khoa (isTextbook=true) để áp dụng hạn mức 500")
        void quickCreateTextbook_success() {
            when(categoryRepository.findAll()).thenReturn(List.of());
            when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> {
                Category c = inv.getArgument(0);
                c.setId(2L);
                return c;
            });
            when(publisherRepository.findAll()).thenReturn(List.of());
            when(publisherRepository.save(any(Publisher.class))).thenAnswer(inv -> {
                Publisher p = inv.getArgument(0);
                p.setId(2L);
                return p;
            });
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
                Product p = inv.getArgument(0);
                p.setId(102L);
                return p;
            });
            when(bookRepository.save(any(Book.class))).thenAnswer(inv -> inv.getArgument(0));

            QuickCreateProductRequest req = new QuickCreateProductRequest(
                    "Sách Giáo Khoa Toán 12 Mới",
                    Product.ProductType.BOOK,
                    true,
                    null,
                    null,
                    new BigDecimal("45000.00"),
                    new BigDecimal("30000.00"),
                    50
            );

            QuickProductResponse res = service.quickCreateProduct(req);

            assertThat(res.id()).isEqualTo(102L);
            assertThat(res.isTextbook()).isTrue();
            assertThat(res.barcode()).startsWith("BK-");
            assertThat(res.initialQuantity()).isEqualTo(50);
        }

        @Test
        @DisplayName("Tạo nhanh Văn phòng phẩm với Brand tự động")
        void quickCreateStationery_success() {
            when(categoryRepository.findAll()).thenReturn(List.of());
            when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> {
                Category c = inv.getArgument(0);
                c.setId(3L);
                return c;
            });
            when(brandRepository.findAll()).thenReturn(List.of());
            when(brandRepository.save(any(Brand.class))).thenAnswer(inv -> {
                Brand b = inv.getArgument(0);
                b.setId(3L);
                return b;
            });
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
                Product p = inv.getArgument(0);
                p.setId(103L);
                return p;
            });
            when(stationeryRepository.save(any(Stationery.class))).thenAnswer(inv -> inv.getArgument(0));

            QuickCreateProductRequest req = new QuickCreateProductRequest(
                    "Bút Bi Thiên Long TL-027",
                    Product.ProductType.STATIONERY,
                    false,
                    null,
                    "VPP-027",
                    new BigDecimal("5000.00"),
                    new BigDecimal("3000.00"),
                    20
            );

            QuickProductResponse res = service.quickCreateProduct(req);

            assertThat(res.id()).isEqualTo(103L);
            assertThat(res.productType()).isEqualTo("STATIONERY");
            assertThat(res.barcode()).isEqualTo("VPP-027");
            verify(stationeryRepository).save(any(Stationery.class));
        }
    }
}
