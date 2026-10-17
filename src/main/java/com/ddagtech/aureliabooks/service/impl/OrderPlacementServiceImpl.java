package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.dto.request.CartQuantityRequest;
import com.ddagtech.aureliabooks.dto.request.CheckoutRequest;
import com.ddagtech.aureliabooks.dto.response.AddressView;
import com.ddagtech.aureliabooks.dto.response.CartView;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.CartRepository;
import com.ddagtech.aureliabooks.repository.OrderRepository;
import com.ddagtech.aureliabooks.repository.UserRepository;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.AddressService;
import com.ddagtech.aureliabooks.service.CartService;
import com.ddagtech.aureliabooks.service.OrderPlacementService;
import com.ddagtech.aureliabooks.service.UserService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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

    @Override
    @Transactional(readOnly = true)
    public CheckoutView getCheckoutView(Long userId, Long shippingAddressId) {
        if (userId == null)
            throw new AppException(ErrorCode.UNAUTHENTICATED);

        CartView cartView = cartService.viewCart(userId);

        if (cartView.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Giỏ hàng đang trống, vui lòng thêm sản phẩm vào giỏ hàng");
        }

        List<AddressView> addressViews = addressService.list(userId);
        AddressView selectedAddress = selectAddress(addressViews, shippingAddressId);
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
    public String placeOrder(Long userId, CheckoutRequest request, CheckoutConfirmation confirmation) {
        if (userId == null ){
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (request == null ){
            throw new AppException(ErrorCode.INVALID_INPUT_DATA);
        }

        var violations = validator.validate(request);
        if (!violations.isEmpty()){
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,violations.iterator().next().getMessage());
        }

        User owner = userRepository.findForCartUpdate(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED, "Người dùng không tồn tại"));

        CustomerIdentity.requireCustomer(owner);

        Order existingOrder = orderRepository.findByOrderCode(request.getCheckoutToken()).orElse(null);

        if (existingOrder!=null){
            if (!Objects.equals(existingOrder.getUser().getId(),userId)) {
                throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            if (existingOrder.getShippingAddress() == null
                    || !Objects.equals(
                    existingOrder.getShippingAddress().getId(),
                    request.getShippingAddressId())
                    || existingOrder.getPaymentMethod() != request.getPaymentMethod()) {
                throw new AppException(ErrorCode.CHECKOUT_CHANGED);
            }

            return existingOrder.getOrderCode();
        }

        validateConfirmation(userId, request, confirmation);

        //check lai
        if (owner.getPhone()==null){
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,"Vui lòng bổ sung số điện thoại trước khi đặt hàng");
        }

        Cart cart = cartRepository.findByUser_Id(userId).orElseThrow(()-> new AppException(ErrorCode.INVALID_INPUT_DATA,"Giỏ hàng trống"));

        List<CartItem> items = new ArrayList<>(cart.getItems());
        if (items.isEmpty()){
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,"Giỏ hàng đang trống");
        }


        return "";
    }

    private AddressView selectAddress(List<AddressView> addressViews, Long shippingAddressId) {
        if (shippingAddressId != null) {
            return addressViews.stream().filter(addressView -> addressView.id().equals(shippingAddressId)).findFirst().orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND, "Địa chỉ không tồn tại"));
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
