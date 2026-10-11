package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.entity.GoodsReceipt;
import com.ddagtech.aureliabooks.entity.GoodsReceiptItem;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.OrderItem;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.GoodsReceiptItemRepository;
import com.ddagtech.aureliabooks.repository.GoodsReceiptRepository;
import com.ddagtech.aureliabooks.repository.OrderItemRepository;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.ddagtech.aureliabooks.service.StockMutationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * FND-03 Implementation: Document-based Stock Posting & Transaction Consistency Engine.
 *
 * Enforces atomic stock updates, pessimistic document/product locking,
 * strict replay protection (idempotency), posting metadata timestamps,
 * and immutable audit logging within the same database transaction.
 *
 * Standalone manual stock quantity adjustments are explicitly forbidden.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockMutationServiceImpl implements StockMutationService {

    private final GoodsReceiptRepository goodsReceiptRepository;
    private final GoodsReceiptItemRepository goodsReceiptItemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * Manager posts a draft goods receipt (UC23/FND-03).
     * Atomically increments inventory balances for all receipt items,
     * transitions status to RECEIVED, stamps received_at and received_by_user_id.
     * Replays are idempotent no-ops.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void receiveReceipt(Long receiptId, Long managerId) {
        if (receiptId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Goods receipt ID must not be null.");
        }
        if (managerId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Manager ID must not be null.");
        }

        User manager = userRepository.findById(managerId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED, "Manager user not found."));

        // Pessimistic lock on source receipt document to prevent concurrent posting
        GoodsReceipt receipt = goodsReceiptRepository.findByIdWithLock(receiptId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Goods receipt not found."));

        // Idempotency / Replay protection: if already RECEIVED or receivedAt stamped, do not post twice
        if (receipt.getStatus() == GoodsReceipt.Status.RECEIVED || receipt.getReceivedAt() != null) {
            log.info("Goods receipt id={} already received at {}. Replay ignored.", receiptId, receipt.getReceivedAt());
            return;
        }

        List<GoodsReceiptItem> items = goodsReceiptItemRepository.findByReceiptId(receiptId);
        if (items.isEmpty() && receipt.getItems() != null && !receipt.getItems().isEmpty()) {
            items = receipt.getItems();
        }
        if (items.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Goods receipt contains no line items.");
        }

        // Aggregate quantities per product in case receipt has multiple items for the same product
        Map<Long, Integer> receivedQtyPerProduct = new HashMap<>();
        for (GoodsReceiptItem item : items) {
            receivedQtyPerProduct.merge(item.getProduct().getId(), item.getReceivedQuantity(), Integer::sum);
        }

        // Lock all affected products in ascending ID order to prevent database deadlocks
        List<Long> productIds = receivedQtyPerProduct.keySet().stream().sorted().toList();
        List<Product> products = productRepository.findAllByIdInWithLock(productIds);
        Map<Long, Product> productMap = products.stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        // Atomic multi-line stock mutation
        for (Map.Entry<Long, Integer> entry : receivedQtyPerProduct.entrySet()) {
            Long productId = entry.getKey();
            Integer quantityToAdd = entry.getValue();
            Product product = productMap.get(productId);
            if (product == null) {
                throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Product not found with ID: " + productId);
            }
            int currentStock = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
            product.setStockQuantity(currentStock + quantityToAdd);
            productRepository.save(product);
        }

        // Stamp posting metadata on the source document
        LocalDateTime now = LocalDateTime.now();
        receipt.setStatus(GoodsReceipt.Status.RECEIVED);
        receipt.setReceivedAt(now);
        receipt.setReceivedBy(manager);
        goodsReceiptRepository.save(receipt);

        // Record audit log within the same database transaction
        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("receiptCode", receipt.getReceiptCode());
        auditDetails.put("managerId", managerId);
        auditDetails.put("postedAt", now.toString());
        auditDetails.put("itemCount", items.size());
        auditDetails.put("quantities", receivedQtyPerProduct);
        auditLogService.record(managerId, "RECEIVE_GOODS", "goods_receipts", receipt.getId(), toJson(auditDetails), null);
    }

    /**
     * Deduct order lines atomically upon checkout/order confirmation (UC11/UC12/FND-03).
     * Validates available stock for ALL lines before modifying any balance.
     * Stamps stock_deducted_at upon success.
     * Replays are idempotent no-ops.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductOrder(Long orderId) {
        if (orderId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Order ID must not be null.");
        }

        // Pessimistic lock on order document
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found."));

        // Idempotency / Replay protection: if already deducted, do not deduct again
        if (order.getStockDeductedAt() != null) {
            log.info("Order id={} stock was already deducted at {}. Replay ignored.", orderId, order.getStockDeductedAt());
            return;
        }

        if (order.getOrderStatus() == Order.OrderStatus.CANCELLED) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Cannot deduct stock for a cancelled order.");
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        if (items.isEmpty() && order.getItems() != null && !order.getItems().isEmpty()) {
            items = order.getItems();
        }
        if (items.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Order contains no line items.");
        }

        // Aggregate required quantity per product across lines
        Map<Long, Integer> requiredQtyPerProduct = new HashMap<>();
        for (OrderItem item : items) {
            requiredQtyPerProduct.merge(item.getProduct().getId(), item.getQuantity(), Integer::sum);
        }

        // Lock all affected products in ascending ID order to prevent deadlocks
        List<Long> productIds = requiredQtyPerProduct.keySet().stream().sorted().toList();
        List<Product> products = productRepository.findAllByIdInWithLock(productIds);
        Map<Long, Product> productMap = products.stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        // Pass 1: Pre-validation of stock sufficiency across ALL lines
        for (Map.Entry<Long, Integer> entry : requiredQtyPerProduct.entrySet()) {
            Long productId = entry.getKey();
            int requiredQty = entry.getValue();
            Product product = productMap.get(productId);
            if (product == null) {
                throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Product not found with ID: " + productId);
            }
            int availableStock = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
            if (availableStock < requiredQty) {
                log.warn("Insufficient stock for product id={}: available={}, required={}", productId, availableStock, requiredQty);
                throw new AppException(ErrorCode.INSUFFICIENT_STOCK,
                        String.format("Insufficient stock for product '%s' (available: %d, required: %d).",
                                product.getTitle() != null ? product.getTitle() : ("ID " + productId),
                                availableStock, requiredQty));
            }
        }

        // Pass 2: Atomic mutation of inventory balances
        for (Map.Entry<Long, Integer> entry : requiredQtyPerProduct.entrySet()) {
            Product product = productMap.get(entry.getKey());
            product.setStockQuantity(product.getStockQuantity() - entry.getValue());
            productRepository.save(product);
        }

        // Stamp posting timestamp on order
        LocalDateTime now = LocalDateTime.now();
        order.setStockDeductedAt(now);
        orderRepository.save(order);

        // Record audit log within the same database transaction
        Long actorId = order.getUser() != null ? order.getUser().getId() : null;
        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("orderCode", order.getOrderCode());
        auditDetails.put("deductedAt", now.toString());
        auditDetails.put("quantities", requiredQtyPerProduct);
        auditLogService.record(actorId, "ORDER_DEDUCT_STOCK", "orders", order.getId(), toJson(auditDetails), null);
    }

    /**
     * Restore stock for cancelled order (UC14/FND-03).
     * Enforces the invariant: only orders that previously had stock deducted are eligible for stock restoration.
     * Orders cancelled before stock deduction do not mutate stock and do not stamp stock_restored_at.
     * Replays are idempotent no-ops.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreCancelledOrder(Long orderId, Long actorUserId) {
        if (orderId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Order ID must not be null.");
        }

        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found."));

        // Idempotency / Replay protection: if already restored, no-op
        if (order.getStockRestoredAt() != null) {
            log.info("Order id={} stock was already restored at {}. Replay ignored.", orderId, order.getStockRestoredAt());
            return;
        }

        // Rule: Only restore stock if stock was actually deducted!
        // If stock was never deducted (e.g. cancelled while in PENDING_PAYMENT before deduction),
        // we do NOT restore product quantities and do NOT set stock_restored_at (per chk_ord_stock_restore).
        if (order.getStockDeductedAt() == null) {
            log.info("Order id={} was never deducted. Stock restoration skipped.", orderId);
            order.setOrderStatus(Order.OrderStatus.CANCELLED);
            if (order.getCancelledAt() == null) {
                order.setCancelledAt(LocalDateTime.now());
            }
            orderRepository.save(order);
            return;
        }

        User actor = actorUserId != null ? userRepository.findById(actorUserId).orElse(null) : null;

        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        if (items.isEmpty() && order.getItems() != null && !order.getItems().isEmpty()) {
            items = order.getItems();
        }

        Map<Long, Integer> restoreQtyPerProduct = new HashMap<>();
        for (OrderItem item : items) {
            restoreQtyPerProduct.merge(item.getProduct().getId(), item.getQuantity(), Integer::sum);
        }

        List<Long> productIds = restoreQtyPerProduct.keySet().stream().sorted().toList();
        List<Product> products = productRepository.findAllByIdInWithLock(productIds);
        Map<Long, Product> productMap = products.stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        for (Map.Entry<Long, Integer> entry : restoreQtyPerProduct.entrySet()) {
            Product product = productMap.get(entry.getKey());
            if (product != null) {
                int currentStock = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
                product.setStockQuantity(currentStock + entry.getValue());
                productRepository.save(product);
            }
        }

        // Stamp posting metadata on order
        LocalDateTime now = LocalDateTime.now();
        order.setStockRestoredAt(now);
        order.setStockRestoreReason(Order.StockRestoreReason.CANCELLED);
        order.setStockRestoredBy(actor);
        order.setOrderStatus(Order.OrderStatus.CANCELLED);
        if (order.getCancelledAt() == null) {
            order.setCancelledAt(now);
        }
        orderRepository.save(order);

        // Record audit log within the same database transaction
        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("orderCode", order.getOrderCode());
        auditDetails.put("restoredAt", now.toString());
        auditDetails.put("actorUserId", actorUserId);
        auditDetails.put("reason", Order.StockRestoreReason.CANCELLED.name());
        auditDetails.put("quantities", restoreQtyPerProduct);
        auditLogService.record(actorUserId, "ORDER_CANCELLED_RESTOCK", "orders", order.getId(), toJson(auditDetails), null);
    }

    /**
     * Staff confirms physical receipt of a successful full return (UC14.1/FND-03).
     * Restores stock immediately and exactly once.
     * Operates independently of payment refund status.
     * Requires staff actor identification per database constraint chk_ord_stock_restore.
     * Replays are idempotent no-ops.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreReturnedOrder(Long orderId, Long staffId) {
        if (orderId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Order ID must not be null.");
        }
        if (staffId == null) {
            throw new AppException(ErrorCode.STAFF_REQUIRED_FOR_RETURN, "Staff ID for return confirmation must not be null.");
        }

        User staff = userRepository.findById(staffId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED, "Staff user not found."));

        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found."));

        // Idempotency / Replay protection: if already restored, no-op
        if (order.getStockRestoredAt() != null) {
            log.info("Order id={} stock was already restored at {}. Replay ignored.", orderId, order.getStockRestoredAt());
            return;
        }

        if (order.getStockDeductedAt() == null) {
            throw new AppException(ErrorCode.ORDER_STOCK_NOT_DEDUCTED, "Order stock was never deducted; cannot restore return stock.");
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        if (items.isEmpty() && order.getItems() != null && !order.getItems().isEmpty()) {
            items = order.getItems();
        }

        Map<Long, Integer> returnQtyPerProduct = new HashMap<>();
        for (OrderItem item : items) {
            returnQtyPerProduct.merge(item.getProduct().getId(), item.getQuantity(), Integer::sum);
        }

        List<Long> productIds = returnQtyPerProduct.keySet().stream().sorted().toList();
        List<Product> products = productRepository.findAllByIdInWithLock(productIds);
        Map<Long, Product> productMap = products.stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        for (Map.Entry<Long, Integer> entry : returnQtyPerProduct.entrySet()) {
            Product product = productMap.get(entry.getKey());
            if (product != null) {
                int currentStock = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
                product.setStockQuantity(currentStock + entry.getValue());
                productRepository.save(product);
            }
        }

        // Stamp posting metadata on order
        LocalDateTime now = LocalDateTime.now();
        order.setStockRestoredAt(now);
        order.setStockRestoreReason(Order.StockRestoreReason.RETURNED);
        order.setStockRestoredBy(staff);
        orderRepository.save(order);

        // Record audit log within the same database transaction
        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("orderCode", order.getOrderCode());
        auditDetails.put("restoredAt", now.toString());
        auditDetails.put("staffId", staffId);
        auditDetails.put("reason", Order.StockRestoreReason.RETURNED.name());
        auditDetails.put("paymentStatus", order.getPaymentStatus().name());
        auditDetails.put("quantities", returnQtyPerProduct);
        auditLogService.record(staffId, "ORDER_RETURN_CONFIRMED", "orders", order.getId(), toJson(auditDetails), null);
    }

    private String toJson(Object object) {
        if (objectMapper == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit log details JSON: {}", e.getMessage());
            return null;
        }
    }
}
