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

/** Xử lý nghiệp vụ UC11; mọi thay đổi order/items/stock/cart/audit của đơn mới cùng một transaction. */
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

    /** Kiểm tra khách đang hoạt động và tra đơn kèm owner để không lộ đơn của người khác. */
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

    /** Tập trung kiểm tra danh tính, sự tồn tại và quyền CUSTOMER cho các truy vấn của UC11. */
    private User checkoutCustomer(Long userId) {
        if (userId == null) throw new AppException(ErrorCode.UNAUTHENTICATED);
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        CustomerIdentity.requireCustomer(owner);
        return owner;
    }

    /** Lấy DTO giỏ và địa chỉ từ các service dùng chung; bước xem trước chưa trừ kho. */
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

    /** Tính cước theo vùng; chưa tích hợp voucher và chưa chốt ngưỡng miễn ship nên các khoản giảm bằng 0. */
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

    /** Tạo đơn nguyên tử với READ_COMMITTED; bất kỳ lỗi nào cũng rollback cả đơn, tồn kho, giỏ và audit. */
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
        // UC11.2 phụ trách voucher; chưa tích hợp thì báo rõ, không âm thầm bỏ mã và đặt nguyên giá.
        if (!normalizeVoucher(request.getVoucherCode()).isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Chức năng áp dụng voucher chưa được tích hợp.");
        }

        // Khóa cùng dòng user mà service giỏ/địa chỉ đang dùng để tuần tự hóa thay đổi của một khách.
        User owner = userRepository.findOwnerForUpdate(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        entityManager.refresh(owner);
        CustomerIdentity.requireCustomer(owner);

        Order existingOrder = orderRepository.findByOrderCode(request.getCheckoutToken()).orElse(null);

        // Nhận diện gửi lại trước khi đọc giỏ: đơn cũ vẫn trả được dù review đã xóa hoặc giỏ có hàng mới.
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

        // Khóa product theo ID tăng dần trước khi FK của order_items tạo shared lock, giảm nguy cơ deadlock.
        // Refresh đọc lại giá/tồn mới nhất, thay dữ liệu có thể đã được OSIV nạp khi xem giỏ.
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

        // Tính lại bằng dữ liệu vừa khóa/đọc; bản review chỉ là mốc đối chiếu, không là nguồn giá để ghi đơn.
        AddressView selectedAddress = addressMapper.toView(address);
        CheckoutView current = checkoutView(cartMapper.toCartView(items),
                List.of(selectedAddress), selectedAddress);
        if (!sameReviewedCheckout(confirmation.reviewedCheckout(), current)) {
            throw new AppException(ErrorCode.CHECKOUT_CHANGED);
        }

        // Cả hai phương thức đều trừ tồn lúc tạo; chỉ khác trạng thái khởi tạo của đơn.
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
            // JPQL UPDATE bỏ qua entity đang được quản lý; refresh để persistence context khớp tồn trong DB.
            entityManager.refresh(item.getProduct());
        }

        // Ghi mốc sau khi tất cả dòng trừ tồn thành công; callback thanh toán không được trừ thêm.
        LocalDateTime postedAt = LocalDateTime.now();
        order.setStockDeductedAt(postedAt);
        cart.getItems().clear(); // orphanRemoval xóa các dòng giỏ trong cùng transaction.
        cart.setUpdatedAt(postedAt);
        entityManager.flush();
        recordPlacement(order);

        return order.getOrderCode();
    }

    /** Dùng lại ràng buộc địa chỉ UC09, gồm SĐT nhận hàng; không kiểm tra xác minh SĐT tài khoản. */
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

    /** Audit tham gia transaction tạo đơn; lỗi chuyển JSON hoặc ghi log phải lan ra để rollback. */
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

    /** So sánh dữ liệu ảnh hưởng quyết định mua; thay đổi phải quay về bước review, không tự đặt theo giá mới. */
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

    /** Lấy đúng địa chỉ đã chọn trong DTO review để đối chiếu nội dung giao hàng. */
    private AddressView selectedAddressOf(CheckoutView view) {
        return view.addressViews().stream()
                .filter(address -> Objects.equals(address.id(), view.selectedAddressId()))
                .findFirst().orElse(null);
    }

    /** So sánh nội dung nhận hàng, bỏ qua cờ mặc định vì cờ này không đổi địa điểm giao. */
    private boolean sameDelivery(AddressView first, AddressView second) {
        // Đổi cờ mặc định hoặc địa chỉ khác trong sổ không làm thay đổi thông tin nhận hàng của đơn này.
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

    /** So tiền theo giá trị số; 100000 và 100000.00 được coi là cùng một mức tiền. */
    private boolean sameMoney(BigDecimal first, BigDecimal second) {
        return first != null && second != null && first.compareTo(second) == 0;
    }

    /** Chuẩn hóa giá từng dòng trước khi dùng equals, tránh báo thay đổi chỉ vì khác scale BigDecimal. */
    private record ReviewedLine(Long productId, String title, Integer quantity, BigDecimal price) {
        static ReviewedLine from(CartItemView item) {
            return new ReviewedLine(item.productId(), item.title(), item.quantity(),
                    item.unitPrice() == null ? null : item.unitPrice().stripTrailingZeros());
        }
    }

    /** ID được chỉ định phải thuộc danh sách của khách; nếu không chỉ định thì dùng địa chỉ mặc định. */
    private AddressView selectAddress(List<AddressView> addressViews, Long shippingAddressId) {
        if (shippingAddressId != null) {
            return addressViews.stream()
                    .filter(address -> address.id().equals(shippingAddressId))
                    .findFirst()
                    .orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND));
        }

        return addressViews.stream().filter(AddressView::isDefault).findFirst().orElse(null);
    }

    /** Áp dụng đúng ba mức cước nghiệp vụ; chưa có vùng hợp lệ thì không tự đoán cước. */
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

    /** Ràng buộc bản xác nhận server với owner, token, địa chỉ, phương thức và voucher của request. */
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

    /** Kiểm tra từng dòng trước khi lưu: số lượng hợp lệ, hàng đang bán, giá hợp lệ và đủ tồn. */
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

    /** Chuẩn hóa khoảng trắng/null để đối chiếu request và review nhất quán. */
    private String normalizeVoucher(String value) {
        return value == null ? "" : value.strip();
    }
}
