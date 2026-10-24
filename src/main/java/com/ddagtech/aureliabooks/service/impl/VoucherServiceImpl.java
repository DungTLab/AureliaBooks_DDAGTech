package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.VoucherForm;
import com.ddagtech.aureliabooks.dto.response.*;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.VoucherMapper;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VoucherServiceImpl implements VoucherService {
    private final VoucherRepository vouchers;
    private final OrderVoucherRepository redemptions;
    private final UserRepository users;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final VoucherMapper mapper;
    private final Validator validator;
    private final Clock promotionClock;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final EntityManager entities;

    @Override
    public Page<VoucherSummary> list(Long actorId, String keyword, Boolean active, Pageable pageable) {
        requireManager(actorId);
        String term = keyword == null ? "" : keyword.strip().toUpperCase(Locale.ROOT);
        if (term.length() > 30) {
            throw invalid("Từ khóa mã giảm giá tối đa 30 ký tự.");
        }
        String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        Page<Voucher> page = vouchers.search(pattern, active, pageable);
        Map<Long, Long> counts = page.isEmpty() ? Map.of() : redemptions.countRedemptions(
                page.getContent().stream().map(Voucher::getId).toList()).stream()
                .collect(Collectors.toMap(OrderVoucherRepository.UsageCount::getVoucherId,
                        OrderVoucherRepository.UsageCount::getRedemptions));
        return page.map(v -> mapper.toSummary(v, counts.getOrDefault(v.getId(), 0L)));
    }

    @Override
    @Transactional
    public Long create(Long actorId, VoucherForm form, String clientIp) {
        User creator = requireManager(actorId);
        if (form == null) {
            throw invalid("Vui lòng nhập thông tin voucher.");
        }
        var errors = validator.validate(form);
        if (!errors.isEmpty()) {
            throw invalid(errors.iterator().next().getMessage());
        }
        if (vouchers.existsByCode(form.code())) {
            throw new AppException(ErrorCode.VOUCHER_CODE_EXISTED);
        }
        Voucher saved;
        try {
            saved = vouchers.saveAndFlush(mapper.toEntity(form, creator));
        } catch (DataIntegrityViolationException ex) {
            throw new AppException(ErrorCode.VOUCHER_CODE_EXISTED);
        }
        record(actorId, "VOUCHER_CREATED", saved.getId(), Map.of("code", saved.getCode(),
                "scope", saved.getScope(), "type", saved.getType(), "remainingUses", saved.getUsageLimit()), clientIp);
        return saved.getId();
    }

    @Override
    @Transactional
    public void setActive(Long actorId, Long voucherId, boolean active, String clientIp) {
        requireManager(actorId);
        Voucher voucher = vouchers.findByIdForUpdate(voucherId)
                .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));
        if (Boolean.valueOf(active).equals(voucher.getActive())) {
            return;
        }
        voucher.setActive(active);
        record(actorId, "VOUCHER_STATUS_CHANGED", voucherId, Map.of("active", active), clientIp);
    }

    @Override
    public VoucherQuote preview(Long customerId, String code, BigDecimal merchandiseSubtotal, BigDecimal shippingFee) {
        requireCustomer(customerId);
        Voucher voucher = vouchers.findByCode(normalizeCode(code))
                .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));
        if (redemptions.existsByUserIdAndVoucherId(customerId, voucher.getId())) {
            throw new AppException(ErrorCode.VOUCHER_ALREADY_USED_BY_USER);
        }
        return calculate(voucher, money(merchandiseSubtotal), money(shippingFee), LocalDateTime.now(promotionClock));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public VoucherQuote redeem(Long customerId, Long orderId, String code, String clientIp) {
        User customer = requireCustomer(customerId);
        Order order = orders.findByIdWithLock(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        if (!Objects.equals(order.getUser().getId(), customerId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        if (order.getOrderStatus() != Order.OrderStatus.PENDING_PAYMENT
                && order.getOrderStatus() != Order.OrderStatus.PENDING_CONFIRMATION) {
            throw new AppException(ErrorCode.INVALID_ORDER_STATE_TRANSITION,
                    "Chỉ áp mã khi đơn hàng đang được tạo và chưa được xử lý.");
        }
        if (order.getPaymentStatus() != Order.PaymentStatus.UNPAID || order.getStockRestoredAt() != null) {
            throw invalid("Không thể thay đổi ưu đãi của đơn đã thanh toán hoặc đã hoàn tồn.");
        }
        String normalized = normalizeCode(code);
        BigDecimal subtotal = subtotal(orderId);
        BigDecimal shipping = money(order.getShippingFee());
        var previous = redemptions.findByOrderId(orderId);
        if (previous.isPresent()) {
            OrderVoucher existing = previous.get();
            if (!existing.getVoucher().getCode().equals(normalized)) {
                throw invalid("Mỗi đơn hàng chỉ được sử dụng một mã giảm giá.");
            }
            return quote(existing.getVoucher(), subtotal, shipping, existing.getDiscountApplied());
        }
        Voucher voucher = vouchers.findByCodeForUpdate(normalized)
                .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));
        if (redemptions.existsByUserIdAndVoucherId(customerId, voucher.getId())) {
            throw new AppException(ErrorCode.VOUCHER_ALREADY_USED_BY_USER);
        }
        LocalDateTime now = LocalDateTime.now(promotionClock);
        VoucherQuote applied = calculate(voucher, subtotal, shipping, now);
        if (vouchers.consumeRemainingUse(voucher.getId(), now) != 1) {
            throw new AppException(ErrorCode.VOUCHER_QUOTA_EXHAUSTED);
        }
        // Bulk update bypasses the persistence context; keep the locked entity consistent for caller reuse.
        entities.refresh(voucher);
        OrderVoucher redemption = new OrderVoucher();
        redemption.setOrder(order);
        redemption.setUser(customer);
        redemption.setVoucher(voucher);
        redemption.setDiscountApplied(applied.discountAmount());
        try {
            redemptions.saveAndFlush(redemption);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent transaction can win the unique user/voucher constraint. Entire placement rolls back.
            throw new AppException(ErrorCode.VOUCHER_ALREADY_USED_BY_USER);
        }
        order.setFinalTotalAmount(applied.payableTotal());
        record(customerId, "VOUCHER_REDEEMED", voucher.getId(),
                Map.of("orderId", orderId, "discount", applied.discountAmount()), clientIp);
        return applied;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseCancelledOrder(Long orderId, Long actorId, String clientIp) {
        Order order = orders.findByIdWithLock(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        if (order.getOrderStatus() != Order.OrderStatus.CANCELLED) {
            throw new AppException(ErrorCode.INVALID_ORDER_STATE_TRANSITION,
                    "Chỉ hoàn lượt sử dụng voucher khi đơn hàng đã được hủy hợp lệ.");
        }
        var existing = redemptions.findByOrderIdForUpdate(orderId);
        if (existing.isEmpty()) {
            return;
        }
        OrderVoucher redemption = existing.get();
        Voucher voucher = vouchers.findByIdForUpdate(redemption.getVoucher().getId())
                .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));
        redemptions.delete(redemption);
        redemptions.flush();
        vouchers.restoreRemainingUse(voucher.getId());
        entities.refresh(voucher);
        record(actorId, "VOUCHER_RELEASED", voucher.getId(), Map.of("orderId", orderId), clientIp);
        // Keep the cancelled order's monetary snapshot; the refund uses the amount actually paid.
    }

    private VoucherQuote calculate(Voucher v, BigDecimal subtotal, BigDecimal shipping, LocalDateTime now) {
        if (!Boolean.TRUE.equals(v.getActive()) || now.isBefore(v.getStartDate()) || !now.isBefore(v.getEndDate())) {
            throw new AppException(ErrorCode.VOUCHER_INVALID_OR_EXPIRED);
        }
        if (v.getUsageLimit() <= 0) {
            throw new AppException(ErrorCode.VOUCHER_QUOTA_EXHAUSTED);
        }
        if (subtotal.signum() <= 0) {
            throw new AppException(ErrorCode.CART_EMPTY);
        }
        if (subtotal.compareTo(v.getMinOrderValue()) < 0) {
            throw new AppException(ErrorCode.VOUCHER_MIN_ORDER_NOT_MET,
                    "Chưa đủ giá trị tối thiểu để sử dụng mã giảm giá này.");
        }
        BigDecimal base = v.getScope() == Voucher.Scope.PRODUCT ? subtotal : shipping;
        if (base.signum() <= 0) {
            throw invalid("Đơn hàng không còn phí vận chuyển để áp dụng mã này.");
        }
        BigDecimal discount;
        if (v.getType() == Voucher.Type.PERCENTAGE) {
            if (v.getMaxDiscountAmount() == null) {
                throw new AppException(ErrorCode.VOUCHER_INVALID_OR_EXPIRED);
            }
            discount = base.multiply(v.getDiscountPercentage()).divide(BigDecimal.valueOf(100));
        } else {
            discount = v.getDiscountAmount();
        }
        if (v.getMaxDiscountAmount() != null) {
            discount = discount.min(v.getMaxDiscountAmount());
        }
        discount = discount.min(base).setScale(2, RoundingMode.HALF_UP);
        if (discount.signum() <= 0) {
            throw new AppException(ErrorCode.VOUCHER_INVALID_OR_EXPIRED);
        }
        return quote(v, subtotal, shipping, discount);
    }

    private VoucherQuote quote(Voucher v, BigDecimal subtotal, BigDecimal shipping, BigDecimal discount) {
        return new VoucherQuote(v.getId(), v.getCode(), v.getScope(), subtotal, shipping, discount,
                subtotal.add(shipping).subtract(discount).setScale(2, RoundingMode.HALF_UP));
    }

    private BigDecimal subtotal(Long orderId) {
        var items = orderItems.findByOrderId(orderId);
        if (items.isEmpty()) {
            throw new AppException(ErrorCode.CART_EMPTY);
        }
        return items.stream().map(item -> money(item.getUnitPriceSnapshot())
                .multiply(BigDecimal.valueOf(item.getQuantity()))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw invalid("Giá trị tiền không hợp lệ.");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw invalid("Giá trị tiền chỉ được có tối đa hai chữ số thập phân.");
        }
    }

    private String normalizeCode(String code) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{1,30}")) {
            throw new AppException(ErrorCode.VOUCHER_NOT_FOUND);
        }
        return normalized;
    }

    private User requireCustomer(Long id) {
        User user = account(id);
        CustomerIdentity.requireCustomer(user);
        return user;
    }

    private User requireManager(Long id) {
        User user = account(id);
        if (!Boolean.TRUE.equals(user.getIsActive()) || user.getRole() == null
                || !Set.of("ROLE_ADMIN", "ROLE_MANAGER").contains(user.getRole().getRoleName())) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    private User account(Long id) {
        if (id == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return users.findById(id).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
    }

    private void record(Long actor, String action, Long target, Map<String, ?> details, String ip) {
        try {
            audit.record(actor, action, "vouchers", target, json.writeValueAsString(details), ip);
        } catch (JsonProcessingException ex) {
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
    }

    private AppException invalid(String message) {
        return new AppException(ErrorCode.INVALID_INPUT_DATA, message);
    }
}
