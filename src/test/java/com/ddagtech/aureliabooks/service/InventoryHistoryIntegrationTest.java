package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.InventoryHistoryFilterRequest;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementResponse;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary.MovementType;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.service.impl.InventoryHistoryServiceImpl;
import com.ddagtech.aureliabooks.service.impl.StockMutationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TASK-06 Test Suite: End-to-End Integration, Replay & View Consistency Verification.
 *
 * Simulates real-time synchronization between the StockMutationService transaction engine
 * and the read-only inventory_movements SQL view (BR-01-04, BR-04-02, BR-04-05).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Inventory History Integration & Replay Verification Tests (TASK-06)")
class InventoryHistoryIntegrationTest {

    // Repositories used by StockMutationServiceImpl
    @Mock private GoodsReceiptRepository goodsReceiptRepository;
    @Mock private GoodsReceiptItemRepository goodsReceiptItemRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;

    // Repositories used by InventoryHistoryServiceImpl
    @Mock private InventoryMovementRepository inventoryMovementRepository;

    private StockMutationService stockMutationService;
    private InventoryHistoryService inventoryHistoryService;

    // In-memory data store simulating database tables
    private Map<Long, GoodsReceipt> receiptStore;
    private Map<Long, List<GoodsReceiptItem>> receiptItemStore;
    private Map<Long, Order> orderStore;
    private Map<Long, List<OrderItem>> orderItemStore;
    private Map<Long, Product> productStore;
    private Map<Long, User> userStore;

    private User manager;
    private User staff;
    private User customer;
    private Product book1;
    private Product book2;

    @BeforeEach
    void setUp() {
        receiptStore = new HashMap<>();
        receiptItemStore = new HashMap<>();
        orderStore = new HashMap<>();
        orderItemStore = new HashMap<>();
        productStore = new HashMap<>();
        userStore = new HashMap<>();

        stockMutationService = new StockMutationServiceImpl(
                goodsReceiptRepository,
                goodsReceiptItemRepository,
                orderRepository,
                orderItemRepository,
                productRepository,
                userRepository,
                auditLogService,
                new ObjectMapper()
        );

        inventoryHistoryService = new InventoryHistoryServiceImpl(inventoryMovementRepository);

        // Seed Users
        manager = User.builder().id(1L).email("manager@aureliabooks.com").fullName("Nguyễn Quản Lý").build();
        staff = User.builder().id(2L).email("staff@aureliabooks.com").fullName("Trần Nhân Viên").build();
        customer = User.builder().id(3L).email("customer@example.com").fullName("Lê Khách Hàng").build();
        userStore.put(1L, manager);
        userStore.put(2L, staff);
        userStore.put(3L, customer);

        // Seed Products
        book1 = Product.builder().id(101L).title("Đắc Nhân Tâm").barcode("893000000001").stockQuantity(20).build();
        book2 = Product.builder().id(102L).title("Dế Mèn Phiêu Lưu Ký").barcode("893000000002").stockQuantity(15).build();
        productStore.put(101L, book1);
        productStore.put(102L, book2);

        // Configure StockMutation Repository Mocks to update in-memory tables
        lenient().when(userRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(userStore.get(inv.getArgument(0))));

        lenient().when(productRepository.findAllByIdInWithLock(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(productStore::get).filter(Objects::nonNull).toList();
        });
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            productStore.put(p.getId(), p);
            return p;
        });

        lenient().when(goodsReceiptRepository.findByIdWithLock(anyLong())).thenAnswer(inv -> Optional.ofNullable(receiptStore.get(inv.getArgument(0))));
        lenient().when(goodsReceiptRepository.save(any(GoodsReceipt.class))).thenAnswer(inv -> {
            GoodsReceipt r = inv.getArgument(0);
            receiptStore.put(r.getId(), r);
            return r;
        });
        lenient().when(goodsReceiptItemRepository.findByReceiptId(anyLong())).thenAnswer(inv -> receiptItemStore.getOrDefault(inv.getArgument(0), List.of()));

        lenient().when(orderRepository.findByIdWithLock(anyLong())).thenAnswer(inv -> Optional.ofNullable(orderStore.get(inv.getArgument(0))));
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            orderStore.put(o.getId(), o);
            return o;
        });
        lenient().when(orderItemRepository.findByOrderId(anyLong())).thenAnswer(inv -> orderItemStore.getOrDefault(inv.getArgument(0), List.of()));

        // Wire InventoryMovementRepository to dynamically evaluate the SQL view definition in real time
        lenient().when(inventoryMovementRepository.searchMovements(any(), any(), any(), any()))
                .thenAnswer(inv -> evaluateVirtualInventoryMovementsView(
                        inv.getArgument(0),
                        inv.getArgument(1),
                        inv.getArgument(2),
                        inv.getArgument(3)
                ));
    }

    /**
     * In-memory simulator of the {@code inventory_movements} SQL view as defined in schema.sql.
     * Evaluates UNION ALL across receipts and orders based on real-time document posting milestones.
     */
    private Page<InventoryMovementResponse> evaluateVirtualInventoryMovementsView(
            InventoryHistoryFilterRequest filter,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive,
            Pageable pageable
    ) {
        List<InventoryMovementResponse> viewRows = new ArrayList<>();

        // BRANCH 1: Inbound goods receipts (Status = 'RECEIVED' and received_at IS NOT NULL)
        for (GoodsReceipt receipt : receiptStore.values()) {
            if (receipt.getStatus() == GoodsReceipt.Status.RECEIVED && receipt.getReceivedAt() != null) {
                List<GoodsReceiptItem> items = receiptItemStore.getOrDefault(receipt.getId(), receipt.getItems());
                if (items != null) {
                    for (GoodsReceiptItem item : items) {
                        Product p = item.getProduct();
                        User actor = receipt.getReceivedBy();
                        viewRows.add(new InventoryMovementResponse(
                                p.getId(), p.getTitle(), p.getBarcode(),
                                MovementType.IMPORT, receipt.getReceivedAt(),
                                item.getReceivedQuantity(),
                                "goods_receipts", receipt.getId(), item.getId(),
                                receipt.getReceiptCode(),
                                actor != null ? actor.getId() : null,
                                actor != null ? actor.getFullName() : null,
                                actor != null ? actor.getEmail() : null,
                                receipt.getNote()
                        ));
                    }
                }
            }
        }

        // BRANCH 2 & 3 & 4: Orders (stock_deducted_at and stock_restored_at milestones)
        for (Order order : orderStore.values()) {
            List<OrderItem> items = orderItemStore.getOrDefault(order.getId(), order.getItems());
            if (items == null) continue;

            // BRANCH 2: Order Deductions
            if (order.getStockDeductedAt() != null) {
                for (OrderItem item : items) {
                    Product p = item.getProduct();
                    User actor = order.getUser();
                    viewRows.add(new InventoryMovementResponse(
                            p.getId(), p.getTitle(), p.getBarcode(),
                            MovementType.ORDER_DEDUCT, order.getStockDeductedAt(),
                            -item.getQuantity(),
                            "orders", order.getId(), item.getId(),
                            order.getOrderCode(),
                            actor != null ? actor.getId() : null,
                            actor != null ? actor.getFullName() : null,
                            actor != null ? actor.getEmail() : null,
                            null
                    ));
                }
            }

            // BRANCH 3 & 4: Order Restocks
            if (order.getStockRestoredAt() != null && order.getStockRestoreReason() != null) {
                MovementType restockType = order.getStockRestoreReason() == Order.StockRestoreReason.CANCELLED
                        ? MovementType.ORDER_CANCELLED_RESTOCK
                        : MovementType.ORDER_RETURNED_RESTOCK;

                for (OrderItem item : items) {
                    Product p = item.getProduct();
                    User actor = order.getStockRestoredBy();
                    viewRows.add(new InventoryMovementResponse(
                            p.getId(), p.getTitle(), p.getBarcode(),
                            restockType, order.getStockRestoredAt(),
                            item.getQuantity(),
                            "orders", order.getId(), item.getId(),
                            order.getOrderCode(),
                            actor != null ? actor.getId() : null,
                            actor != null ? actor.getFullName() : null,
                            actor != null ? actor.getEmail() : null,
                            order.getCancelReason()
                    ));
                }
            }
        }

        // Apply filters
        List<InventoryMovementResponse> filtered = viewRows.stream()
                .filter(row -> filter == null || filter.productId() == null || row.productId().equals(filter.productId()))
                .filter(row -> filter == null || filter.movementType() == null || row.movementType() == filter.movementType())
                .filter(row -> filter == null || filter.actorUserId() == null ||
                        (filter.actorUserId() == -1L && row.actorUserId() == null) ||
                        (filter.actorUserId() != null && filter.actorUserId().equals(row.actorUserId())))
                .filter(row -> fromInclusive == null || !row.occurredAt().isBefore(fromInclusive))
                .filter(row -> toExclusive == null || row.occurredAt().isBefore(toExclusive))
                .filter(row -> {
                    if (filter == null || filter.keyword() == null || filter.keyword().isBlank()) return true;
                    String kw = filter.keyword().toLowerCase();
                    return (row.productTitle() != null && row.productTitle().toLowerCase().contains(kw)) ||
                            (row.productBarcode() != null && row.productBarcode().toLowerCase().contains(kw)) ||
                            (row.referenceCode() != null && row.referenceCode().toLowerCase().contains(kw));
                })
                .sorted(Comparator.comparing(InventoryMovementResponse::occurredAt).reversed())
                .toList();

        int pageSize = pageable != null ? pageable.getPageSize() : 20;
        int pageNumber = pageable != null ? pageable.getPageNumber() : 0;
        int start = Math.min(pageNumber * pageSize, filtered.size());
        int end = Math.min(start + pageSize, filtered.size());

        List<InventoryMovementResponse> pageContent = filtered.subList(start, end);
        return new PageImpl<>(pageContent, pageable != null ? pageable : PageRequest.of(0, 20), filtered.size());
    }

    @Nested
    @DisplayName("Scenario 1 & 2: Inbound Goods Receipts Invariants (BR-01-03, BR-01-04)")
    class InboundReceiptTests {

        @Test
        @DisplayName("Scenario 1: DRAFT goods receipts NEVER generate movements in view")
        void draftReceipt_neverGeneratesInventoryMovements() {
            // Setup a DRAFT goods receipt with 2 line items
            GoodsReceipt draftReceipt = GoodsReceipt.builder()
                    .id(1L)
                    .receiptCode("GR-20261021-001")
                    .status(GoodsReceipt.Status.DRAFT)
                    .note("Bản nháp chuẩn bị kiểm đếm")
                    .build();

            GoodsReceiptItem item1 = GoodsReceiptItem.builder().id(11L).receipt(draftReceipt).product(book1).receivedQuantity(50).unitCost(BigDecimal.valueOf(50000)).build();
            GoodsReceiptItem item2 = GoodsReceiptItem.builder().id(12L).receipt(draftReceipt).product(book2).receivedQuantity(30).unitCost(BigDecimal.valueOf(30000)).build();

            draftReceipt.setItems(List.of(item1, item2));
            receiptStore.put(1L, draftReceipt);
            receiptItemStore.put(1L, List.of(item1, item2));

            // Query history
            Page<InventoryMovementResponse> history = inventoryHistoryService.getInventoryHistory(
                    new InventoryHistoryFilterRequest(), PageRequest.of(0, 20));

            // Must be completely empty
            assertThat(history.isEmpty()).isTrue();
            assertThat(history.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("Scenario 2: Posting receipt creates atomic positive IMPORT movements")
        void receivedReceiptPosting_createsPositiveImportMovements() {
            // Setup DRAFT receipt
            GoodsReceipt receipt = GoodsReceipt.builder()
                    .id(2L)
                    .receiptCode("GR-20261021-002")
                    .status(GoodsReceipt.Status.DRAFT)
                    .note("Nhập chính thức từ NXB")
                    .build();

            GoodsReceiptItem item1 = GoodsReceiptItem.builder().id(21L).receipt(receipt).product(book1).receivedQuantity(100).unitCost(BigDecimal.valueOf(50000)).build();
            GoodsReceiptItem item2 = GoodsReceiptItem.builder().id(22L).receipt(receipt).product(book2).receivedQuantity(60).unitCost(BigDecimal.valueOf(40000)).build();
            receipt.setItems(List.of(item1, item2));
            receiptStore.put(2L, receipt);
            receiptItemStore.put(2L, List.of(item1, item2));

            // Post receipt via StockMutationService
            stockMutationService.receiveReceipt(2L, manager.getId());

            // Product balances incremented
            assertThat(productStore.get(101L).getStockQuantity()).isEqualTo(20 + 100);
            assertThat(productStore.get(102L).getStockQuantity()).isEqualTo(15 + 60);

            // Query history view
            Page<InventoryMovementResponse> history = inventoryHistoryService.getInventoryHistory(
                    new InventoryHistoryFilterRequest(), PageRequest.of(0, 20));

            assertThat(history.getTotalElements()).isEqualTo(2);
            assertThat(history.getContent()).allMatch(m -> m.movementType() == MovementType.IMPORT);
            assertThat(history.getContent()).allMatch(InventoryMovementResponse::isInflow);
            assertThat(history.getContent()).allMatch(m -> m.quantityChange() > 0);
            assertThat(history.getContent()).allMatch(m -> m.actorName().equals("Nguyễn Quản Lý"));
            assertThat(history.getContent()).allMatch(m -> m.referenceCode().equals("GR-20261021-002"));
        }

        @Test
        @DisplayName("Scenario 3: Replay idempotency guarantees view records are NEVER duplicated")
        void replayReceiptPosting_neverDuplicatesHistoryViewRows() {
            GoodsReceipt receipt = GoodsReceipt.builder()
                    .id(3L)
                    .receiptCode("GR-20261021-003")
                    .status(GoodsReceipt.Status.DRAFT)
                    .note("Kiểm tra chống lặp")
                    .build();

            GoodsReceiptItem item1 = GoodsReceiptItem.builder().id(31L).receipt(receipt).product(book1).receivedQuantity(40).unitCost(BigDecimal.valueOf(50000)).build();
            receipt.setItems(List.of(item1));
            receiptStore.put(3L, receipt);
            receiptItemStore.put(3L, List.of(item1));

            // Initial post
            stockMutationService.receiveReceipt(3L, manager.getId());
            Page<InventoryMovementResponse> firstQuery = inventoryHistoryService.getInventoryHistory(
                    new InventoryHistoryFilterRequest(), PageRequest.of(0, 20));
            assertThat(firstQuery.getTotalElements()).isEqualTo(1);

            // Replay call (second attempt)
            stockMutationService.receiveReceipt(3L, manager.getId());
            Page<InventoryMovementResponse> replayQuery = inventoryHistoryService.getInventoryHistory(
                    new InventoryHistoryFilterRequest(), PageRequest.of(0, 20));

            // Total rows must strictly remain 1, NEVER 2!
            assertThat(replayQuery.getTotalElements()).isEqualTo(1);
            assertThat(productStore.get(101L).getStockQuantity()).isEqualTo(20 + 40); // Not 20 + 80
        }
    }

    @Nested
    @DisplayName("Scenario 4 & 5: Order Checkout, Cancellation & Return Restock (BR-04-02, BR-04-05)")
    class OrderLifecycleTests {

        @Test
        @DisplayName("Scenario 4: Order checkout deduction creates negative ORDER_DEDUCT movements")
        void orderCheckout_createsNegativeOrderDeductMovements() {
            Order order = Order.builder()
                    .id(10L)
                    .orderCode("ORD-20261021-010")
                    .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                    .user(customer)
                    .build();

            OrderItem oi1 = OrderItem.builder().id(101L).order(order).product(book1).quantity(3).unitPriceSnapshot(BigDecimal.valueOf(100000)).productNameSnapshot("Đắc Nhân Tâm").build();
            OrderItem oi2 = OrderItem.builder().id(102L).order(order).product(book2).quantity(2).unitPriceSnapshot(BigDecimal.valueOf(80000)).productNameSnapshot("Dế Mèn").build();
            order.setItems(List.of(oi1, oi2));
            orderStore.put(10L, order);
            orderItemStore.put(10L, List.of(oi1, oi2));

            // Checkout stock deduction
            stockMutationService.deductOrder(10L);

            // Stock decremented
            assertThat(productStore.get(101L).getStockQuantity()).isEqualTo(20 - 3);
            assertThat(productStore.get(102L).getStockQuantity()).isEqualTo(15 - 2);

            // Query history
            Page<InventoryMovementResponse> history = inventoryHistoryService.getInventoryHistory(
                    new InventoryHistoryFilterRequest(), PageRequest.of(0, 20));

            assertThat(history.getTotalElements()).isEqualTo(2);
            assertThat(history.getContent()).allMatch(m -> m.movementType() == MovementType.ORDER_DEDUCT);
            assertThat(history.getContent()).allMatch(InventoryMovementResponse::isOutflow);
            assertThat(history.getContent()).allMatch(m -> m.quantityChange() < 0);
            assertThat(history.getContent()).allMatch(m -> m.referenceCode().equals("ORD-20261021-010"));
        }

        @Test
        @DisplayName("Scenario 4a: Order cancellation after deduction creates positive ORDER_CANCELLED_RESTOCK movements")
        void orderCancellationAfterDeduction_createsCancelledRestockMovements() {
            Order order = Order.builder()
                    .id(20L)
                    .orderCode("ORD-20261021-020")
                    .orderStatus(Order.OrderStatus.PENDING_CONFIRMATION)
                    .user(customer)
                    .build();

            OrderItem oi1 = OrderItem.builder().id(201L).order(order).product(book1).quantity(5).unitPriceSnapshot(BigDecimal.valueOf(100000)).productNameSnapshot("Đắc Nhân Tâm").build();
            order.setItems(List.of(oi1));
            orderStore.put(20L, order);
            orderItemStore.put(20L, List.of(oi1));

            // Step 1: Deduct stock
            stockMutationService.deductOrder(20L);
            assertThat(productStore.get(101L).getStockQuantity()).isEqualTo(20 - 5);

            // Step 2: Cancel order
            stockMutationService.restoreCancelledOrder(20L, customer.getId());
            assertThat(productStore.get(101L).getStockQuantity()).isEqualTo(20); // restored back

            // Query history: now has 2 movements for this order (DEDUCT and RESTOCK)
            Page<InventoryMovementResponse> history = inventoryHistoryService.getInventoryHistory(
                    new InventoryHistoryFilterRequest(), PageRequest.of(0, 20));

            assertThat(history.getTotalElements()).isEqualTo(2);

            InventoryMovementResponse deductRow = history.getContent().stream()
                    .filter(m -> m.movementType() == MovementType.ORDER_DEDUCT).findFirst().orElseThrow();
            assertThat(deductRow.quantityChange()).isEqualTo(-5);

            InventoryMovementResponse restockRow = history.getContent().stream()
                    .filter(m -> m.movementType() == MovementType.ORDER_CANCELLED_RESTOCK).findFirst().orElseThrow();
            assertThat(restockRow.quantityChange()).isEqualTo(5);
            assertThat(restockRow.isInflow()).isTrue();
        }

        @Test
        @DisplayName("Scenario 4b: Staff confirmed return creates positive ORDER_RETURNED_RESTOCK movements")
        void orderReturnConfirmed_createsReturnedRestockMovements() {
            Order order = Order.builder()
                    .id(30L)
                    .orderCode("ORD-20261021-030")
                    .orderStatus(Order.OrderStatus.DELIVERED)
                    .paymentStatus(Order.PaymentStatus.PAID)
                    .user(customer)
                    .build();

            OrderItem oi1 = OrderItem.builder().id(301L).order(order).product(book2).quantity(4).unitPriceSnapshot(BigDecimal.valueOf(80000)).productNameSnapshot("Dế Mèn").build();
            order.setItems(List.of(oi1));
            orderStore.put(30L, order);
            orderItemStore.put(30L, List.of(oi1));

            // Deduct stock
            stockMutationService.deductOrder(30L);

            // Staff confirms return
            stockMutationService.restoreReturnedOrder(30L, staff.getId());

            // Query history
            Page<InventoryMovementResponse> history = inventoryHistoryService.getInventoryHistory(
                    new InventoryHistoryFilterRequest(null, null, MovementType.ORDER_RETURNED_RESTOCK, null, null, null, 0, 20),
                    PageRequest.of(0, 20)
            );

            assertThat(history.getTotalElements()).isEqualTo(1);
            InventoryMovementResponse returnRow = history.getContent().getFirst();
            assertThat(returnRow.movementType()).isEqualTo(MovementType.ORDER_RETURNED_RESTOCK);
            assertThat(returnRow.quantityChange()).isEqualTo(4);
            assertThat(returnRow.actorName()).isEqualTo("Trần Nhân Viên");
        }

        @Test
        @DisplayName("Scenario 5: Order cancelled before stock deduction NEVER creates restock movements")
        void orderCancelledBeforeDeduction_neverCreatesRestockMovements() {
            // Order created in PENDING_PAYMENT, stock was NEVER deducted (stockDeductedAt == null)
            Order unDeductedOrder = Order.builder()
                    .id(40L)
                    .orderCode("ORD-20261021-040")
                    .orderStatus(Order.OrderStatus.PENDING_PAYMENT)
                    .user(customer)
                    .build();

            OrderItem oi1 = OrderItem.builder().id(401L).order(unDeductedOrder).product(book1).quantity(2).unitPriceSnapshot(BigDecimal.valueOf(100000)).productNameSnapshot("Đắc Nhân Tâm").build();
            unDeductedOrder.setItems(List.of(oi1));
            orderStore.put(40L, unDeductedOrder);
            orderItemStore.put(40L, List.of(oi1));

            // Customer cancels order before checkout stock deduction completes
            stockMutationService.restoreCancelledOrder(40L, customer.getId());

            // Stock should not be modified
            assertThat(productStore.get(101L).getStockQuantity()).isEqualTo(20);

            // Query history
            Page<InventoryMovementResponse> history = inventoryHistoryService.getInventoryHistory(
                    new InventoryHistoryFilterRequest(), PageRequest.of(0, 20));

            // Zero movements in view
            assertThat(history.isEmpty()).isTrue();
            assertThat(history.getTotalElements()).isZero();
        }
    }

    @Nested
    @DisplayName("Scenario 6: Cross-Referencing & Drill-Down Filtering Tests")
    class DrillDownFilteringTests {

        @Test
        @DisplayName("Filters history by movement type and keyword independently")
        void filtering_isolatesSpecificMovementStreams() {
            // Receipt 1
            GoodsReceipt receipt = GoodsReceipt.builder().id(50L).receiptCode("GR-20261021-050").status(GoodsReceipt.Status.RECEIVED).receivedAt(LocalDateTime.now()).receivedBy(manager).build();
            GoodsReceiptItem gri = GoodsReceiptItem.builder().id(501L).receipt(receipt).product(book1).receivedQuantity(10).build();
            receipt.setItems(List.of(gri));
            receiptStore.put(50L, receipt);
            receiptItemStore.put(50L, List.of(gri));

            // Order 1
            Order order = Order.builder().id(60L).orderCode("ORD-20261021-060").stockDeductedAt(LocalDateTime.now()).user(customer).build();
            OrderItem oi = OrderItem.builder().id(601L).order(order).product(book2).quantity(2).unitPriceSnapshot(BigDecimal.valueOf(80000)).productNameSnapshot("Dế Mèn").build();
            order.setItems(List.of(oi));
            orderStore.put(60L, order);
            orderItemStore.put(60L, List.of(oi));

            // Filter only IMPORT
            Page<InventoryMovementResponse> onlyImports = inventoryHistoryService.getInventoryHistory(
                    new InventoryHistoryFilterRequest(null, null, MovementType.IMPORT, null, null, null, 0, 20),
                    PageRequest.of(0, 20)
            );
            assertThat(onlyImports.getTotalElements()).isEqualTo(1);
            assertThat(onlyImports.getContent().getFirst().referenceCode()).isEqualTo("GR-20261021-050");

            // Filter only ORDER_DEDUCT
            Page<InventoryMovementResponse> onlyDeductions = inventoryHistoryService.getInventoryHistory(
                    new InventoryHistoryFilterRequest(null, null, MovementType.ORDER_DEDUCT, null, null, null, 0, 20),
                    PageRequest.of(0, 20)
            );
            assertThat(onlyDeductions.getTotalElements()).isEqualTo(1);
            assertThat(onlyDeductions.getContent().getFirst().referenceCode()).isEqualTo("ORD-20261021-060");

            // Keyword filter by book title
            Page<InventoryMovementResponse> keywordMatch = inventoryHistoryService.getInventoryHistory(
                    new InventoryHistoryFilterRequest("Mèn Phiêu Lưu", null, (MovementType) null, null, null, null, 0, 20),
                    PageRequest.of(0, 20)
            );
            assertThat(keywordMatch.getTotalElements()).isEqualTo(1);
            assertThat(keywordMatch.getContent().getFirst().productTitle()).isEqualTo("Dế Mèn Phiêu Lưu Ký");
        }
    }
}
