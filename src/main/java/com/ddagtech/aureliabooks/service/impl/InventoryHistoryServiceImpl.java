package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.InventoryHistoryFilterRequest;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementResponse;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.dto.response.OrderDetailModalDto;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.OrderItem;
import com.ddagtech.aureliabooks.repository.InventoryMovementRepository;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.ddagtech.aureliabooks.service.InventoryHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Read-only UC25 service implementation backed by the {@code inventory_movements} SQL view.
 * Strictly enforces date chronological invariants and read-only transaction boundaries.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryHistoryServiceImpl implements InventoryHistoryService {

    private final InventoryMovementRepository inventoryMovementRepository;
    private final OrderRepository orderRepository;

    @Override
    public Page<InventoryMovementSummary> history(
            Long productId, Long actorUserId,
            LocalDateTime fromInclusive, LocalDateTime toExclusive, Pageable pageable) {
        return inventoryMovementRepository.findMovements(
                productId, actorUserId, fromInclusive, toExclusive, pageable);
    }

    @Override
    public Page<InventoryMovementResponse> getInventoryHistory(
            InventoryHistoryFilterRequest filter,
            Pageable pageable) {

        InventoryHistoryFilterRequest safeFilter = filter != null
                ? filter
                : new InventoryHistoryFilterRequest();

        // Validate date chronological invariant (RDS Exception 1.0.E1)
        if (!safeFilter.isDateRangeValid()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Start date must be earlier than or equal to End date.");
        }

        // Normalize date boundaries: [fromInclusive, toExclusive)
        LocalDateTime fromInclusive = safeFilter.startDate() != null
                ? safeFilter.startDate().atStartOfDay()
                : null;
        LocalDateTime toExclusive = safeFilter.endDate() != null
                ? safeFilter.endDate().plusDays(1).atStartOfDay()
                : null;

        // Sanitize pagination parameters against allowable whitelist
        Pageable safePageable = normalizePageable(safeFilter, pageable);

        return inventoryMovementRepository.searchMovements(
                safeFilter,
                fromInclusive,
                toExclusive,
                safePageable
        );
    }

    private Pageable normalizePageable(InventoryHistoryFilterRequest filter, Pageable pageable) {
        int page = 0;
        int size = InventoryHistoryFilterRequest.DEFAULT_SIZE;

        if (pageable != null) {
            page = Math.max(0, pageable.getPageNumber());
            int reqSize = pageable.getPageSize();
            size = InventoryHistoryFilterRequest.ALLOWED_SIZES.contains(reqSize)
                    ? reqSize
                    : InventoryHistoryFilterRequest.DEFAULT_SIZE;
        } else if (filter != null) {
            page = filter.page() != null ? Math.max(0, filter.page()) : 0;
            Integer reqSize = filter.size();
            size = (reqSize != null && InventoryHistoryFilterRequest.ALLOWED_SIZES.contains(reqSize))
                    ? reqSize
                    : InventoryHistoryFilterRequest.DEFAULT_SIZE;
        }

        return PageRequest.of(page, size);
    }
    @Override
    public Optional<OrderDetailModalDto> getOrderDetail(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return orderRepository.findByIdWithItemsAndProduct(orderId)
                .map(this::mapToOrderDetailDto);
    }

    private OrderDetailModalDto mapToOrderDetailDto(Order order) {
        List<OrderDetailModalDto.OrderItemDetailDto> items = new ArrayList<>();
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                BigDecimal price = item.getUnitPriceSnapshot() != null ? item.getUnitPriceSnapshot() : BigDecimal.ZERO;
                int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                BigDecimal subtotal = price.multiply(BigDecimal.valueOf(qty));
                String barcode = (item.getProduct() != null && item.getProduct().getBarcode() != null)
                        ? item.getProduct().getBarcode()
                        : "-";
                items.add(new OrderDetailModalDto.OrderItemDetailDto(
                        item.getProduct() != null ? item.getProduct().getId() : null,
                        item.getProductNameSnapshot(),
                        barcode,
                        qty,
                        price,
                        subtotal
                ));
            }
        }

        return new OrderDetailModalDto(
                order.getId(),
                order.getOrderCode(),
                order.getCreatedAt(),
                order.getOrderStatus() != null ? order.getOrderStatus().name() : "-",
                order.getPaymentMethod() != null ? order.getPaymentMethod().name() : "-",
                order.getPaymentStatus() != null ? order.getPaymentStatus().name() : "-",
                order.getShippingRecipientName(),
                order.getShippingPhone(),
                order.getShippingFullAddress(),
                order.getShippingFee(),
                order.getFinalTotalAmount(),
                order.getCancelReason(),
                order.getStockDeductedAt(),
                order.getStockRestoredAt(),
                order.getStockRestoreReason() != null ? order.getStockRestoreReason().name() : null,
                items
        );
    }
}