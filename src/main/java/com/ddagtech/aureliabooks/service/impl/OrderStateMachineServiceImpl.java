package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.OrderMapper;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.repository.specification.OrderQueueSpecification;
import com.ddagtech.aureliabooks.service.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderStateMachineServiceImpl implements OrderStateMachineService {
    private final OrderRepository orders;
    private final UserRepository users;
    private final OrderMapper mapper;
    private final OrderStatusValidator statuses;
    private final StockMutationService stock;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final Validator validator;
    private final EntityManager entityManager;
    private final OrderAuditRepository auditEntries;

    @Override
    public Page<StaffOrderQueueView> getOrderQueue(OrderQueueFilter filter, Pageable pageable, Long staffId) {
        requireStaff(staffId);
        if (filter == null) filter = new OrderQueueFilter(null, null, null, null, null);
        if (filter.fromDate() != null && filter.toDate() != null && filter.fromDate().isAfter(filter.toDate()))
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.");
        if (filter.toDate() != null && filter.toDate().getYear() >= 9999)
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Ngày kết thúc không hợp lệ.");
        var paging = PageRequest.of(pageable.isPaged() ? pageable.getPageNumber() : 0,
                pageable.isPaged() ? Math.min(100, pageable.getPageSize()) : 20,
                Sort.by(Sort.Direction.ASC, "createdAt", "id"));
        return orders.findAll(OrderQueueSpecification.matching(filter), paging).map(o -> new StaffOrderQueueView(
                o.getId(), o.getOrderCode(), o.getShippingRecipientName(), o.getCreatedAt(), o.getOrderStatus(),
                o.getPaymentMethod(), o.getPaymentStatus(), o.getFinalTotalAmount(),
                o.getUser().getFullName(), o.getUser().getEmail()));
    }

    @Override
    public PackagingChecklistDTO getPackagingChecklist(Long orderId, Long staffId) {
        User actor = requireStaff(staffId);
        Order order = findOrder(orderId);
        ensureOperational(order);
        List<Order.OrderStatus> targets = statuses.allowedTargets(order.getOrderStatus()).stream()
                .filter(target -> target != Order.OrderStatus.CANCELLED || !unverifiedPayment(order))
                .filter(target -> !isManager(actor) || target == Order.OrderStatus.CANCELLED)
                .filter(target -> target == Order.OrderStatus.CANCELLED || processingPaymentValid(order))
                .filter(target -> order.getStockDeductedAt() != null && order.getStockRestoredAt() == null)
                .filter(target -> target != Order.OrderStatus.CANCELLED
                        || (order.getShippedAt() == null && order.getDeliveredAt() == null
                            && order.getPaymentStatus() != Order.PaymentStatus.REFUNDED)).toList();
        boolean canConfirmReturn = !isManager(actor)
                && order.getOrderStatus() == Order.OrderStatus.DELIVERED
                && order.getStockDeductedAt() != null && order.getStockRestoredAt() == null;
        boolean refundBasis = refundBasis(order);
        return new PackagingChecklistDTO(order.getId(), mapper.toConfirmation(order),
                order.getTrackingNumber(), order.getCancelReason(), mapper.toTimeline(order), targets,
                order.getUser().getFullName(), order.getUser().getEmail(), history(order),
                !isManager(actor) && refundBasis && !hasRefund(orderId), refundBasis,
                canConfirmReturn, order.getStockRestoredAt(), order.getStockRestoreReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transitionStatus(Long orderId, OrderTransitionRequest request, Long staffId) {
        User actor = requireStaff(staffId);
        if (request == null || !validator.validate(request).isEmpty())
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Thông tin cập nhật không hợp lệ. Vui lòng tải lại đơn hàng.");
        if (orderId == null) throw new AppException(ErrorCode.ORDER_NOT_FOUND);
        Order order = orders.findByIdWithLock(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        // A locking query may return an already cached entity. Refresh under the same lock before validation.
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        ensureOperational(order);
        var previous = order.getOrderStatus();
        var target = request.targetStatus();
        if (isManager(actor) && target != Order.OrderStatus.CANCELLED) throw new AppException(ErrorCode.UNAUTHORIZED);
        statuses.validateTransition(previous, target);
        if (order.getStockRestoredAt() != null)
            throw new AppException(ErrorCode.INVALID_ORDER_STATE_TRANSITION, "Đơn hàng đã được hoàn tồn kho, không thể chuyển trạng thái tiếp tục xử lý.");
        if (order.getStockDeductedAt() == null)
            throw new AppException(ErrorCode.ORDER_STOCK_NOT_DEDUCTED);
        var previousPayment = order.getPaymentStatus();
        if (target == Order.OrderStatus.CANCELLED) {
            if (request.reason() == null || request.reason().isBlank())
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Nhân viên phải nhập lý do hủy đơn.");
            if (unverifiedPayment(order)) throw new AppException(ErrorCode.ORDER_PAYMENT_OUTCOME_UNVERIFIED);
            if (order.getShippedAt() != null || order.getDeliveredAt() != null || order.getStockRestoredAt() != null)
                throw new AppException(ErrorCode.ORDER_NOT_ELIGIBLE_FOR_CANCEL);
            if (order.getPaymentStatus() == Order.PaymentStatus.REFUNDED)
                throw new AppException(ErrorCode.ORDER_NOT_ELIGIBLE_FOR_CANCEL);
            order.setCancelReason(request.reason().strip());
            // The order lock is held until commit; the stock engine joins this transaction.
            stock.restoreCancelledOrder(orderId, staffId);
        } else {
            if (!processingPaymentValid(order))
                throw new AppException(ErrorCode.ORDER_NOT_ELIGIBLE_FOR_PAYMENT, "Trạng thái thanh toán không phù hợp: COD phải chưa thanh toán, VNPay phải đã thanh toán.");
            if (target == Order.OrderStatus.DELIVERED && order.getPaymentMethod() == Order.PaymentMethod.COD
                    && !Boolean.TRUE.equals(request.codCollected()))
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Chưa xác nhận đã thu tiền COD. Đơn hàng chưa được cập nhật.");
            LocalDateTime now = LocalDateTime.now();
            order.setOrderStatus(target);
            switch (target) {
                case CONFIRMED -> order.setConfirmedAt(now);
                case SHIPPING -> {
                    order.setShippedAt(now);
                    if (request.trackingNumber() != null && !request.trackingNumber().isBlank())
                        order.setTrackingNumber(request.trackingNumber().strip());
                }
                case DELIVERED -> {
                    order.setDeliveredAt(now);
                    if (order.getPaymentMethod() == Order.PaymentMethod.COD) order.setPaymentStatus(Order.PaymentStatus.PAID);
                }
                default -> throw new IllegalStateException("Unreachable staff transition");
            }
        }
        orders.flush();
        var details = new LinkedHashMap<String, Object>();
        details.put("orderCode", order.getOrderCode());
        details.put("from", previous.name());
        details.put("to", target.name());
        details.put("reason", target == Order.OrderStatus.CANCELLED ? order.getCancelReason() : null);
        details.put("trackingNumber", order.getTrackingNumber());
        details.put("paymentFrom", previousPayment.name());
        details.put("paymentTo", order.getPaymentStatus().name());
        details.put("codCollected", Boolean.TRUE.equals(request.codCollected()) && target == Order.OrderStatus.DELIVERED);
        record(staffId, "ORDER_STATUS_CHANGED", orderId, details);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReturnRestock(Long orderId, ReturnConfirmationRequest request, Long staffId) {
        User actor = requireStaff(staffId);
        if (isManager(actor)) throw new AppException(ErrorCode.UNAUTHORIZED);
        if (request == null || !validator.validate(request).isEmpty() || !Boolean.TRUE.equals(request.fullReturnVerified()))
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Bắt buộc kiểm đếm nhận lại đầy đủ 100% số lượng mọi dòng sản phẩm và nhập lý do hoàn.");
        if (orderId == null) throw new AppException(ErrorCode.ORDER_NOT_FOUND);
        Order order = orders.findByIdWithLock(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        ensureOperational(order);

        if (order.getOrderStatus() != Order.OrderStatus.DELIVERED)
            throw new AppException(ErrorCode.INVALID_ORDER_STATE_TRANSITION, "Chỉ có thể xác nhận nhận hàng hoàn khi đơn đã giao thành công (DELIVERED).");

        if (order.getStockRestoredAt() != null)
            throw new AppException(ErrorCode.ORDER_STOCK_ALREADY_RESTORED);

        // Call stock engine to restore quantities exactly once, stamp stock_restored_at, stock_restore_reason = RETURNED, and record ORDER_RETURN_CONFIRMED
        stock.restoreReturnedOrder(orderId, staffId);
        order.setOrderStatus(Order.OrderStatus.RETURNED);

        var details = new LinkedHashMap<String, Object>();
        details.put("orderCode", order.getOrderCode());
        details.put("orderStatus", order.getOrderStatus().name());
        details.put("paymentStatus", order.getPaymentStatus().name());
        details.put("returnReason", request.returnReason().strip());
        details.put("from", Order.OrderStatus.DELIVERED.name());
        details.put("to", Order.OrderStatus.RETURNED.name());
        details.put("inspectionNote", blank(request.inspectionNote()) ? null : request.inspectionNote().strip());
        record(staffId, "ORDER_RETURN_INSPECTION_CONFIRMED", orderId, details);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmExternalRefund(Long orderId, RefundConfirmationRequest request, Long staffId) {
        User actor = requireStaff(staffId);
        if (isManager(actor)) throw new AppException(ErrorCode.UNAUTHORIZED);
        if (request == null || !validator.validate(request).isEmpty())
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Nhập đủ số tiền, phương thức, ngày hoàn, thông tin chứng minh và xác nhận đã hoàn tiền.");
        if (orderId == null) throw new AppException(ErrorCode.ORDER_NOT_FOUND);
        Order order = orders.findByIdWithLock(orderId).orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        ensureOperational(order);
        if (!refundBasis(order) || hasRefund(orderId)) throw new AppException(ErrorCode.ORDER_NOT_ELIGIBLE_FOR_REFUND);
        if (request.amount().compareTo(order.getFinalTotalAmount()) != 0)
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Chỉ xác nhận hoàn toàn bộ số tiền của đơn, không vượt quá số tiền có thể hoàn.");
        order.setPaymentStatus(Order.PaymentStatus.REFUNDED);
        var details = new LinkedHashMap<String, Object>();
        details.put("orderCode", order.getOrderCode());
        details.put("amount", request.amount());
        details.put("method", request.method().strip());
        details.put("completedDate", request.completedDate().toString());
        details.put("supportingInfo", blank(request.supportingInfo()) ? null : request.supportingInfo().strip());
        details.put("paymentFrom", Order.PaymentStatus.PAID.name());
        details.put("paymentTo", Order.PaymentStatus.REFUNDED.name());
        details.put("basis", order.getOrderStatus() == Order.OrderStatus.CANCELLED ? "PAID_CANCELLATION" : "RECEIVED_RETURN");
        orders.flush();
        record(staffId, "ORDER_REFUND_CONFIRMED", orderId, details);
    }

    private void record(Long staffId, String action, Long orderId, Map<String, Object> details) {
        try {
            audit.recordRequired(staffId, action, "orders", orderId, json.writeValueAsString(details), null);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize order audit", exception);
        }
    }

    private boolean refundBasis(Order order) {
        return order.getPaymentStatus() == Order.PaymentStatus.PAID
                && order.getFinalTotalAmount() != null && order.getFinalTotalAmount().signum() > 0
                && (order.getOrderStatus() == Order.OrderStatus.CANCELLED
                    || (order.getOrderStatus() == Order.OrderStatus.RETURNED
                        && order.getStockRestoreReason() == Order.StockRestoreReason.RETURNED
                        && order.getStockRestoredAt() != null && order.getStockRestoredBy() != null));
    }

    private boolean hasRefund(Long id) {
        return !auditEntries.findByTargetTableAndTargetIdAndActionInOrderByCreatedAtAscIdAsc(
                "orders", id, List.of("ORDER_REFUND_CONFIRMED")).isEmpty();
    }

    private List<OrderActivityView> history(Order order) {
        var result = new ArrayList<OrderActivityView>();
        for (AuditLog entry : auditEntries.findByTargetTableAndTargetIdOrderByCreatedAtAscIdAsc("orders", order.getId())) {
            String description = entry.getAction();
            try {
                var data = json.readTree(entry.getDetailsJson() == null ? "{}" : entry.getDetailsJson());
                if (entry.getAction().equals("ORDER_REFUND_CONFIRMED")) {
                    description = "Hoàn tiền: " + data.path("amount").asText() + " đ; " + data.path("method").asText()
                            + "; ngày " + data.path("completedDate").asText()
                            + "; chứng minh: " + data.path("supportingInfo").asText("");
                    if (data.hasNonNull("reference")) description += "; mã: " + data.path("reference").asText();
                } else if (entry.getAction().equals("ORDER_STATUS_CHANGED")) {
                    description = data.path("from").asText() + " → " + data.path("to").asText()
                            + "; thanh toán: " + data.path("paymentFrom").asText() + " → " + data.path("paymentTo").asText();
                    if (data.hasNonNull("reason")) description += "; lý do: " + data.path("reason").asText();
                } else if (entry.getAction().equals("ORDER_RETURN_CONFIRMED")) {
                    description = "Đã nhận lại toàn bộ hàng hoàn về kho; lý do: " + data.path("reason").asText();
                } else if (entry.getAction().equals("ORDER_RETURN_INSPECTION_CONFIRMED")) {
                    description = "Xác nhận kiểm hàng hoàn: " + data.path("returnReason").asText();
                    if (data.hasNonNull("inspectionNote") && !data.path("inspectionNote").asText().isBlank()) {
                        description += "; ghi chú: " + data.path("inspectionNote").asText();
                    }
                }
            } catch (JsonProcessingException exception) {
                // Legacy malformed payloads still display the recorded action, actor and timestamp.
            }
            result.add(new OrderActivityView(entry.getCreatedAt(), entry.getUser() == null ? "Hệ thống" : entry.getUser().getFullName(),
                    entry.getAction(), description));
        }
        if (order.getVnpayPayDate() != null)
            result.add(new OrderActivityView(null, "VNPay", "VNPAY_PAYMENT_RECORD", "Ngày thanh toán ghi nhận: "
                    + order.getVnpayPayDate() + "; ngân hàng: " + Objects.toString(order.getVnpayBankCode(), "")
                    + "; mã giao dịch: " + Objects.toString(order.getVnpayTxnRef(), "")));
        return List.copyOf(result);
    }

    private boolean processingPaymentValid(Order order) {
        return order.getPaymentMethod() == Order.PaymentMethod.COD ? order.getPaymentStatus() == Order.PaymentStatus.UNPAID
                : order.getPaymentStatus() == Order.PaymentStatus.PAID;
    }

    private void ensureOperational(Order order) {
        if (order.getOrderStatus() == Order.OrderStatus.PENDING_PAYMENT) throw new AppException(ErrorCode.ORDER_NOT_FOUND);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private boolean isManager(User actor) { return "ROLE_MANAGER".equals(actor.getRole().getRoleName()); }

    private Order findOrder(Long id) {
        if (id == null) throw new AppException(ErrorCode.ORDER_NOT_FOUND);
        return orders.findById(id).orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
    }

    private boolean unverifiedPayment(Order order) {
        return order.getPaymentMethod() == Order.PaymentMethod.VNPAY && order.getPaymentStatus() == Order.PaymentStatus.UNPAID;
    }

    private User requireStaff(Long id) {
        if (id == null) throw new AppException(ErrorCode.UNAUTHENTICATED);
        User staff = users.findById(id).orElseThrow(() -> new AppException(ErrorCode.UNAUTHENTICATED));
        if (!Boolean.TRUE.equals(staff.getIsActive())) throw new AppException(ErrorCode.ACCOUNT_LOCKED);
        if (staff.getRole() == null || !Set.of("ROLE_SALE_STAFF", "ROLE_MANAGER", "ROLE_ADMIN").contains(staff.getRole().getRoleName()))
            throw new AppException(ErrorCode.UNAUTHORIZED);
        return staff;
    }
}
