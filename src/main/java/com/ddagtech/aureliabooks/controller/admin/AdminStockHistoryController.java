package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.dto.request.InventoryHistoryFilterRequest;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementResponse;
import com.ddagtech.aureliabooks.dto.response.InventoryMovementSummary.MovementType;
import com.ddagtech.aureliabooks.dto.response.OrderDetailModalDto;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.OrderItem;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.service.InventoryHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Controller managing administrative inspection of inventory movement history (UC25 / SCR-DB-08).
 * Enforces strict read-only access aggregated directly from source documents (goods_receipts and orders).
 * Access is protected by Spring Security Lean RBAC and restricted to Store Manager and Admin roles.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AdminStockHistoryController {

    private final InventoryHistoryService inventoryHistoryService;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    /**
     * Renders the paginated stock movement ledger grid with multi-criteria filtering.
     * Enforces Exception 1.0.E1 validation on form parameters without aborting execution.
     *
     * @param filter request filter criteria (keyword, product, movementType, date range, pagination)
     * @param bindingResult form validation binding results
     * @param page zero-based page index (defaults to 0)
     * @param size page size (defaults to 20 per Wireframe SCR-DB-08)
     * @param model Spring MVC model
     * @return view template name "admin/stock/history"
     */
    @GetMapping({"/manager/stock/history", "/manager/inventory/history"})
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public String list(
            @ModelAttribute("filter") InventoryHistoryFilterRequest filter,
            BindingResult bindingResult,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model
    ) {
        InventoryHistoryFilterRequest safeFilter = filter != null
                ? filter
                : new InventoryHistoryFilterRequest();

        // Enforce Exception 1.0.E1: Start date must be earlier than or equal to End date
        if (!safeFilter.isDateRangeValid()) {
            bindingResult.rejectValue("startDate", "invalid.date.range",
                    "Từ ngày không được lớn hơn Đến ngày.");
        }
        // Enforce RDS 5.5 Step 4: Dates cannot be in the future (L-05)
        if (!safeFilter.isNotFutureDate()) {
            bindingResult.rejectValue("endDate", "invalid.future.date",
                    "Ngày lọc không được vượt quá ngày hiện tại.");
        }

        Page<InventoryMovementResponse> movementsPage;
        int validPage = Math.max(0, page);
        int validSize = (size > 0 && InventoryHistoryFilterRequest.ALLOWED_SIZES.contains(size))
                ? size
                : InventoryHistoryFilterRequest.DEFAULT_SIZE;
        Pageable pageable = PageRequest.of(validPage, validSize);

        if (bindingResult.hasErrors()) {
            log.warn("Invalid inventory history filter parameters: {}", bindingResult.getAllErrors());
            movementsPage = Page.empty(pageable);
        } else {
            movementsPage = inventoryHistoryService.getInventoryHistory(safeFilter, pageable);
        }

        // Populate staff/admin actors for operator filter (L-08)
        List<User> actors = userRepository.findAll().stream()
                .filter(u -> u.getRole() != null && !"CUSTOMER".equalsIgnoreCase(u.getRole().getRoleName()))
                .toList();

        model.addAttribute("movementsPage", movementsPage);
        model.addAttribute("movementTypes", MovementType.values());
        model.addAttribute("actors", actors);
        model.addAttribute("filter", safeFilter);
        model.addAttribute("currentPage", validPage);
        model.addAttribute("pageSize", validSize);

        log.debug("Rendered inventory movements ledger: totalElements={}, page={}/{}",
                movementsPage.getTotalElements(), validPage, movementsPage.getTotalPages());

        return "admin/stock/history";
    }

    /**
     * AJAX endpoint to inspect source order details in modal popup (RDS 5.5 Step 7 / L-02).
     */
    @GetMapping({"/api/orders/{id}", "/manager/orders/api/{id}"})
    @ResponseBody
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN', 'SALE_STAFF')")
    @Transactional(readOnly = true)
    public ResponseEntity<OrderDetailModalDto> getOrderApi(@PathVariable Long id) {
        return orderRepository.findById(id)
                .map(order -> {
                    List<OrderDetailModalDto.OrderItemDetailDto> items = new ArrayList<>();
                    if (order.getItems() != null) {
                        for (OrderItem item : order.getItems()) {
                            BigDecimal price = item.getUnitPriceSnapshot() != null ? item.getUnitPriceSnapshot() : BigDecimal.ZERO;
                            BigDecimal subtotal = price.multiply(BigDecimal.valueOf(item.getQuantity()));
                            String barcode = item.getProduct() != null ? item.getProduct().getBarcode() : "-";
                            items.add(new OrderDetailModalDto.OrderItemDetailDto(
                                    item.getProduct() != null ? item.getProduct().getId() : null,
                                    item.getProductNameSnapshot(),
                                    barcode,
                                    item.getQuantity(),
                                    price,
                                    subtotal
                            ));
                        }
                    }

                    return ResponseEntity.ok(new OrderDetailModalDto(
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
                    ));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
