package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.dto.request.CartQuantityRequest;
import com.ddagtech.aureliabooks.dto.request.CheckoutRequest;
import com.ddagtech.aureliabooks.dto.request.AddressRequest;
import com.ddagtech.aureliabooks.dto.response.AddressView;
import com.ddagtech.aureliabooks.dto.response.CartItemView;
import com.ddagtech.aureliabooks.dto.response.CartView;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.dto.response.OrderConfirmationView;
import com.ddagtech.aureliabooks.entity.Cart;
import com.ddagtech.aureliabooks.entity.CartItem;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.ShippingAddress;
import com.ddagtech.aureliabooks.entity.User;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.CartMapper;
import com.ddagtech.aureliabooks.mapper.OrderMapper;
import com.ddagtech.aureliabooks.mapper.ShippingAddressMapper;
import com.ddagtech.aureliabooks.repository.CartRepository;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.ShippingAddressRepository;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.AddressService;
import com.ddagtech.aureliabooks.service.CartService;
import com.ddagtech.aureliabooks.service.OrderPlacementService;
import com.ddagtech.aureliabooks.service.AuditLogService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class OrderPlacementServiceImpl implements OrderPlacementService {
    private final CartService cartService;
    private final AddressService addressService;
    private final Validator validator;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final ShippingAddressRepository shippingAddressRepository;
    private final EntityManager entityManager;
    private final CartMapper cartMapper;
    private final ShippingAddressMapper addressMapper;
    private final OrderMapper orderMapper;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public OrderConfirmationView getOrderConfirmation(Long userId, String orderCode) {
        checkoutCustomer(userId);
        if (orderCode == null || !orderCode.matches("[a-f0-9]{32}")) {
            throw new AppException(ErrorCode.ORDER_NOT_FOUND);
        }
        Order order = orderRepository.findByOrderCodeAndUser_Id(orderCode, userId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        return orderMapper.toConfirmation(order);
    }

    private User checkoutCustomer(Long userId) {
        if (userId == null) throw new AppException(ErrorCode.UNAUTHENTICATED);
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        CustomerIdentity.requireCustomer(owner);
        return owner;
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutView getCheckoutView(Long userId, Long shippingAddressId) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        CartView cartView = cartService.viewCart(userId);

        if (cartView.isEmpty()) {
            throw new AppException(ErrorCode.CART_EMPTY);
        }

        List<AddressView> addressViews = addressService.list(userId);
        AddressView selectedAddress = selectAddress(addressViews, shippingAddressId);
        return checkoutView(cartView, addressViews, selectedAddress);
    }

    private CheckoutView checkoutView(CartView cartView, List<AddressView> addressViews,
                                      AddressView selectedAddress) {
        BigDecimal subTotal = cartView.merchandiseSubtotal();
        BigDecimal productDiscount = BigDecimal.ZERO;
        BigDecimal shippingDiscount = BigDecimal.ZERO;
        BigDecimal baseShippingFee = null;
        BigDecimal shippingFee = null;
        BigDecimal finalTotalAmount = null;

        if (selectedAddress != null) {
            baseShippingFee = calculateBaseShippingFee(selectedAddress);

            shippingFee = baseShippingFee.subtract(shippingDiscount);

            finalTotalAmount = subTotal
                    .subtract(productDiscount)
                    .add(shippingFee);
        }
        return new CheckoutView(
                cartView.items(),
                addressViews,
                selectedAddress == null ? null : selectedAddress.id(),
                subTotal,
                productDiscount,
                baseShippingFee,
                shippingDiscount,
                shippingFee,
                finalTotalAmount
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public String placeOrder(Long userId, CheckoutRequest request, CheckoutConfirmation confirmation) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA);
        }

        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, violations.iterator().next().getMessage());
        }
        // UC11.2 owns voucher validation and redemption. Never silently ignore a supplied code.
        if (!normalizeVoucher(request.getVoucherCode()).isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Chức năng áp dụng voucher chưa được tích hợp.");
        }

        // AddressService and CartService lock this same owner row before mutations.
        User owner = userRepository.findOwnerForUpdate(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        entityManager.refresh(owner);
        CustomerIdentity.requireCustomer(owner);

        Order existingOrder = orderRepository.findByOrderCode(request.getCheckoutToken()).orElse(null);

        // Check replay before inspecting the cart or requiring the original session confirmation.
        if (existingOrder != null) {
            if (!Objects.equals(existingOrder.getUser().getId(), userId)) {
                throw new AppException(ErrorCode.ORDER_NOT_FOUND);
            }
            if (existingOrder.getShippingAddress() == null
                    || !Objects.equals(existingOrder.getShippingAddress().getId(),
                                       request.getShippingAddressId())
                    || existingOrder.getPaymentMethod() != request.getPaymentMethod()) {
                throw new AppException(ErrorCode.CHECKOUT_CHANGED);
            }

            return existingOrder.getOrderCode();
        }

        validateConfirmation(userId, request, confirmation);

        Cart cart = cartRepository.findByUser_Id(userId)
                .orElseThrow(() -> new AppException(ErrorCode.CART_EMPTY));

        List<CartItem> items = new ArrayList<>(cart.getItems());
        if (items.isEmpty()) {
            throw new AppException(ErrorCode.CART_EMPTY);
        }

        // Lock before inserting order lines (their FKs otherwise acquire product shared locks).
        // Refresh also replaces Product values previously loaded by OSIV/cart rendering.
        items.sort(Comparator.comparing(item -> item.getProduct().getId()));
        for (CartItem item : items) {
            entityManager.refresh(item.getProduct(), LockModeType.PESSIMISTIC_WRITE);
            validateOrderItem(item);
        }

        ShippingAddress address = shippingAddressRepository
                .findByIdAndUserIdAndIsActiveTrue(request.getShippingAddressId(), userId)
                .orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND));
        entityManager.refresh(address);
        if (!Boolean.TRUE.equals(address.getIsActive())
                || !Objects.equals(address.getUser().getId(), userId)) {
            throw new AppException(ErrorCode.ADDRESS_NOT_FOUND);
        }
        validateShippingAddress(address);

        AddressView selectedAddress = addressMapper.toView(address);
        CheckoutView current = checkoutView(cartMapper.toCartView(items),
                List.of(selectedAddress), selectedAddress);
        if (!sameReviewedCheckout(confirmation.reviewedCheckout(), current)) {
            throw new AppException(ErrorCode.CHECKOUT_CHANGED);
        }

        Order.OrderStatus status = request.getPaymentMethod() == Order.PaymentMethod.COD
                ? Order.OrderStatus.PENDING_CONFIRMATION : Order.OrderStatus.PENDING_PAYMENT;
        Order order = orderMapper.toNewOrder(owner, address, items, request, current, status);
        orderRepository.saveAndFlush(order);

        for (CartItem item : items) {
            if (productRepository.deductStockIfAvailable(item.getProduct().getId(),
                    item.getQuantity()) != 1) {
                throw new AppException(ErrorCode.INSUFFICIENT_STOCK,
                        "Không đủ tồn kho cho sản phẩm: " + item.getProduct().getTitle());
            }
            // Bulk JPQL bypasses managed entities; synchronize before any subsequent flush.
            entityManager.refresh(item.getProduct());
        }

        LocalDateTime postedAt = LocalDateTime.now();
        order.setStockDeductedAt(postedAt);
        cart.getItems().clear(); // orphanRemoval deletes cart_items in this transaction.
        cart.setUpdatedAt(postedAt);
        entityManager.flush();
        recordPlacement(order);

        return order.getOrderCode();
    }

    private void validateShippingAddress(ShippingAddress address) {
        AddressRequest delivery = new AddressRequest(address.getRecipientName(), address.getPhone(),
                address.getEconomicRegion(), address.getProvince(), address.getDistrict(),
                address.getWard(), address.getDetailedAddress(), address.getIsDefault());
        var violations = validator.validate(delivery);
        if (!violations.isEmpty() || orderMapper.fullAddress(address).length() > 500) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Thông tin giao hàng không hợp lệ hoặc địa chỉ vượt quá 500 ký tự.");
        }
    }

    private void recordPlacement(Order order) {
        try {
            String details = objectMapper.writeValueAsString(Map.of(
                    "orderCode", order.getOrderCode(),
                    "paymentMethod", order.getPaymentMethod().name(),
                    "orderStatus", order.getOrderStatus().name(),
                    "stockDeductedAt", order.getStockDeductedAt().toString()));
            auditLogService.record(order.getUser().getId(), "ORDER_PLACED", "orders",
                    order.getId(), details, null);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể ghi nhận nhật ký đặt hàng.", exception);
        }
    }

    private boolean sameReviewedCheckout(CheckoutView reviewed, CheckoutView current) {
        List<ReviewedLine> oldLines = reviewed.items().stream().map(ReviewedLine::from)
                .sorted(Comparator.comparing(ReviewedLine::productId)).toList();
        List<ReviewedLine> newLines = current.items().stream().map(ReviewedLine::from)
                .sorted(Comparator.comparing(ReviewedLine::productId)).toList();
        return oldLines.equals(newLines)
                && sameDelivery(selectedAddressOf(reviewed), selectedAddressOf(current))
                && sameMoney(reviewed.merchandiseSubtotal(), current.merchandiseSubtotal())
                && sameMoney(reviewed.productDiscount(), current.productDiscount())
                && sameMoney(reviewed.baseShippingFee(), current.baseShippingFee())
                && sameMoney(reviewed.shippingDiscount(), current.shippingDiscount())
                && sameMoney(reviewed.shippingFee(), current.shippingFee())
                && sameMoney(reviewed.finalTotalAmount(), current.finalTotalAmount());
    }

    private AddressView selectedAddressOf(CheckoutView view) {
        return view.addressViews().stream()
                .filter(address -> Objects.equals(address.id(), view.selectedAddressId()))
                .findFirst().orElse(null);
    }

    private boolean sameDelivery(AddressView first, AddressView second) {
        // Changing the default flag or an unrelated address does not change this delivery.
        return first != null && second != null
                && Objects.equals(first.id(), second.id())
                && Objects.equals(first.recipientName(), second.recipientName())
                && Objects.equals(first.phone(), second.phone())
                && first.economicRegion() == second.economicRegion()
                && Objects.equals(first.province(), second.province())
                && Objects.equals(first.district(), second.district())
                && Objects.equals(first.ward(), second.ward())
                && Objects.equals(first.detailedAddress(), second.detailedAddress());
    }

    private boolean sameMoney(BigDecimal first, BigDecimal second) {
        return first != null && second != null && first.compareTo(second) == 0;
    }

    private record ReviewedLine(Long productId, String title, Integer quantity, BigDecimal price) {
        static ReviewedLine from(CartItemView item) {
            return new ReviewedLine(item.productId(), item.title(), item.quantity(),
                    item.unitPrice() == null ? null : item.unitPrice().stripTrailingZeros());
        }
    }

    private AddressView selectAddress(List<AddressView> addressViews, Long shippingAddressId) {
        if (shippingAddressId != null) {
            return addressViews.stream()
                    .filter(address -> address.id().equals(shippingAddressId))
                    .findFirst()
                    .orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND));
        }

        return addressViews.stream().filter(AddressView::isDefault).findFirst().orElse(null);
    }

    private BigDecimal calculateBaseShippingFee(AddressView address) {
        if (address.economicRegion() == null) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT_DATA,
                    "Địa chỉ chưa có vùng tính phí giao hàng hợp lệ."
            );
        }

        return switch (address.economicRegion()) {
            case NORTHERN -> new BigDecimal("50000");
            case CENTRAL_HIGHLANDS -> new BigDecimal("40000");
            case SOUTHERN_MEKONG -> new BigDecimal("30000");
        };
    }

    private void validateConfirmation(Long userId, CheckoutRequest request, CheckoutConfirmation confirmation) {
        if (confirmation == null
                || confirmation.reviewedCheckout() == null
                || !Objects.equals(confirmation.userId(), userId)
                || !Objects.equals(
                confirmation.checkoutToken(),
                request.getCheckoutToken())
                || !Objects.equals(
                confirmation.reviewedCheckout().selectedAddressId(),
                request.getShippingAddressId())
                || confirmation.paymentMethod() != request.getPaymentMethod()
                || !normalizeVoucher(confirmation.voucherCode()).equals(
                normalizeVoucher(request.getVoucherCode()))) {
            throw new AppException(
                    ErrorCode.CHECKOUT_CHANGED,
                    "Phiên xác nhận không còn phù hợp. Vui lòng xác nhận lại."
            );
        }
    }

    private void validateOrderItem(CartItem item) {
        Integer quantity = item.getQuantity();
        Product product = item.getProduct();

        if (quantity == null
                || quantity < 1
                || quantity > CartQuantityRequest.MAX_QUANTITY) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT_DATA,
                    "Số lượng sản phẩm phải từ 1 đến 99."
            );
        }

        if (!Boolean.TRUE.equals(product.getIsActive())) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT_DATA,
                    "Sản phẩm hiện không được bán: " + product.getTitle()
            );
        }

        if (product.getPrice() == null
                || product.getPrice().signum() < 0) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT_DATA,
                    "Giá sản phẩm không hợp lệ."
            );
        }

        if (product.getStockQuantity() == null
                || product.getStockQuantity() < quantity) {
            throw new AppException(
                    ErrorCode.INSUFFICIENT_STOCK,
                    "Không đủ tồn kho cho sản phẩm: " + product.getTitle()
            );
        }
    }

    private String normalizeVoucher(String value) {
        return value == null ? "" : value.strip();
    }
}
