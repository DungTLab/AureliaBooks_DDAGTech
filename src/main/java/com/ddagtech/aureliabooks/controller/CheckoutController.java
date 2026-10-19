package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.CheckoutConfirmation;
import com.ddagtech.aureliabooks.dto.request.AddressRequest;
import com.ddagtech.aureliabooks.dto.request.CheckoutRequest;
import com.ddagtech.aureliabooks.dto.request.CheckoutSelectionRequest;
import com.ddagtech.aureliabooks.dto.response.CheckoutView;
import com.ddagtech.aureliabooks.dto.response.OrderConfirmationView;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.AddressService;
import com.ddagtech.aureliabooks.service.CheckoutPaymentGateway;
import com.ddagtech.aureliabooks.service.OrderPlacementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;

@Slf4j
@Controller
@RequiredArgsConstructor
public class CheckoutController {
    private final OrderPlacementService orders;
    private final AddressService addresses;
    private final CustomerIdentity identity;
    private final CheckoutSessionStore reviews;
    private final ObjectProvider<CheckoutPaymentGateway> payments;

    @GetMapping("/checkout")
    public String checkout(Authentication auth, @RequestParam(required = false) Long shippingAddressId,
                           @RequestParam(defaultValue = "COD") Order.PaymentMethod paymentMethod, Model model) {
        Long owner = identity.resolve(auth);
        CheckoutView checkout = orders.getCheckoutView(owner, shippingAddressId);
        addCheckout(model, checkout, paymentMethod);
        if (!model.containsAttribute("addressForm")) {
            model.addAttribute("addressForm", new AddressRequest(null, null, null,
                    null, null, null, null, false));
        }
        return "checkout/view";
    }

    @PostMapping("/checkout/review")
    public String prepare(Authentication auth,
                          @Valid @ModelAttribute("selection") CheckoutSelectionRequest selection,
                          BindingResult errors, HttpServletRequest request, RedirectAttributes flash) {
        Long owner = identity.resolve(auth);
        if (errors.hasErrors()) return invalidForm(errors, flash);
        if (selection.voucherCode() != null && !selection.voucherCode().isBlank()) {
            flash.addFlashAttribute("errorMessage", "Mã giảm giá chưa thể sử dụng lúc này.");
            return "redirect:/checkout";
        }
        if (selection.paymentMethod() == Order.PaymentMethod.VNPAY && !paymentAvailable()) {
            flash.addFlashAttribute("errorMessage", "Thanh toán trực tuyến tạm thời chưa khả dụng. Vui lòng chọn COD.");
            return "redirect:/checkout";
        }
        CheckoutView view = orders.getCheckoutView(owner, selection.shippingAddressId());
        if (hasInvalidItems(view)) {
            flash.addFlashAttribute("errorMessage", "Một số sản phẩm cần được cập nhật. Vui lòng kiểm tra giỏ hàng.");
            return "redirect:/cart";
        }
        CheckoutConfirmation review = reviews.save(request.getSession(), owner, view,
                selection.paymentMethod(), null);
        return "redirect:/checkout/review/" + review.checkoutToken();
    }

    @GetMapping("/checkout/review/{token}")
    public String review(Authentication auth, @PathVariable String token,
                         HttpServletRequest request, Model model) {
        Long owner = identity.resolve(auth);
        CheckoutConfirmation review = reviews.find(request.getSession(false), token, owner);
        if (review == null) throw new AppException(ErrorCode.CHECKOUT_CHANGED,
                "Phiên xác nhận đã hết hiệu lực. Vui lòng kiểm tra lại đơn hàng.");
        model.addAttribute("review", review);
        model.addAttribute("checkout", review.reviewedCheckout());
        model.addAttribute("delivery", review.reviewedCheckout().addressViews().stream()
                .filter(address -> address.id().equals(review.reviewedCheckout().selectedAddressId()))
                .findFirst().orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND)));
        model.addAttribute("onlinePaymentAvailable", paymentAvailable());
        return "checkout/review";
    }

    @PostMapping("/checkout/place")
    public String place(Authentication auth, @Valid @ModelAttribute("checkoutForm") CheckoutRequest form,
                        BindingResult errors, HttpServletRequest request, RedirectAttributes flash) {
        Long owner = identity.resolve(auth);
        if (errors.hasErrors()) return invalidForm(errors, flash);
        CheckoutConfirmation review = reviews.find(request.getSession(false), form.getCheckoutToken(), owner);
        // Never create a new online order when there is nowhere to hand payment over.
        if (form.getPaymentMethod() == Order.PaymentMethod.VNPAY && !paymentAvailable()) {
            flash.addFlashAttribute("errorMessage", "Thanh toán trực tuyến tạm thời chưa khả dụng. Vui lòng chọn COD.");
            return "redirect:/checkout";
        }
        String code = orders.placeOrder(owner, form, review);
        reviews.remove(request.getSession(false), form.getCheckoutToken());
        if (form.getPaymentMethod() == Order.PaymentMethod.VNPAY) return startPayment(owner, code, flash);
        flash.addFlashAttribute("successMessage", "Đặt hàng thành công. Đơn hàng đang chờ cửa hàng xác nhận.");
        return "redirect:/orders/" + code;
    }

    @GetMapping("/orders/{orderCode}")
    public String confirmation(Authentication auth, @PathVariable String orderCode, Model model) {
        OrderConfirmationView order = orders.getOrderConfirmation(identity.resolve(auth), orderCode);
        model.addAttribute("order", order);
        model.addAttribute("onlinePaymentAvailable", paymentAvailable());
        return "checkout/order-confirm";
    }

    @PostMapping("/orders/{orderCode}/pay")
    public String pay(Authentication auth, @PathVariable String orderCode, RedirectAttributes flash) {
        Long owner = identity.resolve(auth);
        OrderConfirmationView order = orders.getOrderConfirmation(owner, orderCode);
        if (!order.awaitingOnlinePayment()) {
            flash.addFlashAttribute("errorMessage", "Đơn hàng hiện không có khoản thanh toán trực tuyến đang chờ.");
            return "redirect:/orders/" + orderCode;
        }
        return startPayment(owner, orderCode, flash);
    }

    @PostMapping("/checkout/addresses")
    public String addAddress(Authentication auth, @Valid @ModelAttribute("addressForm") AddressRequest form,
                             BindingResult errors, Model model, RedirectAttributes flash) {
        Long owner = identity.resolve(auth);
        if (errors.hasErrors()) {
            addCheckout(model, orders.getCheckoutView(owner, null), Order.PaymentMethod.COD);
            model.addAttribute("addressFormOpen", true);
            return "checkout/view";
        }
        Long addressId = addresses.create(owner, form);
        flash.addFlashAttribute("successMessage", "Đã lưu địa chỉ giao hàng.");
        return "redirect:/checkout?shippingAddressId=" + addressId;
    }

    private void addCheckout(Model model, CheckoutView view, Order.PaymentMethod method) {
        model.addAttribute("checkout", view);
        model.addAttribute("selectedPaymentMethod", method);
        model.addAttribute("onlinePaymentAvailable", paymentAvailable());
        model.addAttribute("itemsReady", !hasInvalidItems(view));
    }

    private boolean hasInvalidItems(CheckoutView view) {
        return view.items().stream().anyMatch(item -> item.warningMessage() != null
                || !item.availableForSale() || item.quantity() > item.availableStock());
    }

    private boolean paymentAvailable() {
        CheckoutPaymentGateway gateway = payments.getIfAvailable();
        return gateway != null && gateway.isAvailable();
    }

    private String startPayment(Long owner, String code, RedirectAttributes flash) {
        try {
            CheckoutPaymentGateway gateway = payments.getIfAvailable();
            if (gateway == null || !gateway.isAvailable()) throw new IllegalStateException("Payment unavailable");
            URI destination = URI.create(gateway.startPayment(owner, code));
            if (!"https".equalsIgnoreCase(destination.getScheme()) || destination.getHost() == null
                    || destination.getUserInfo() != null) throw new IllegalStateException("Invalid payment destination");
            return "redirect:" + destination;
        } catch (RuntimeException error) {
            log.error("Unable to start payment for committed order {}", code, error);
            flash.addFlashAttribute("errorMessage", "Đơn hàng đã được tạo nhưng chưa thể mở cổng thanh toán. Bạn có thể thử lại từ trang đơn hàng.");
            return "redirect:/orders/" + code;
        }
    }

    private String invalidForm(BindingResult errors, RedirectAttributes flash) {
        var error = errors.getAllErrors().getFirst();
        flash.addFlashAttribute("errorMessage", error.getCode() != null && error.getCode().startsWith("typeMismatch")
                ? "Thông tin đặt hàng không hợp lệ. Vui lòng kiểm tra lại." : error.getDefaultMessage());
        return "redirect:/checkout";
    }

    @ExceptionHandler(AppException.class)
    public Object businessError(AppException error, HttpServletRequest request, HttpServletResponse response,
                                Authentication auth, RedirectAttributes flash) {
        ErrorCode code = error.getErrorCode();
        if (code == ErrorCode.ORDER_NOT_FOUND) {
            return new ModelAndView("error/404", HttpStatus.NOT_FOUND);
        }
        flash.addFlashAttribute("errorMessage", error.getMessage());
        if (code == ErrorCode.UNAUTHENTICATED || code == ErrorCode.ACCOUNT_LOCKED
                || code == ErrorCode.USER_NOT_EXISTED) {
            new SecurityContextLogoutHandler().logout(request, response, auth);
            return "redirect:/auth/login";
        }
        if (code == ErrorCode.UNAUTHORIZED) return "redirect:/error/403";
        if (code == ErrorCode.CHECKOUT_CHANGED) {
            reviews.remove(request.getSession(false), request.getParameter("checkoutToken"));
        }
        if (code == ErrorCode.CART_EMPTY || code == ErrorCode.INSUFFICIENT_STOCK
                || code == ErrorCode.PRODUCT_NOT_FOUND) return "redirect:/cart";
        return "redirect:/checkout";
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public String invalidParameters(RedirectAttributes flash) {
        flash.addFlashAttribute("errorMessage", "Địa chỉ hoặc phương thức thanh toán không hợp lệ.");
        return "redirect:/checkout";
    }

    @ExceptionHandler(Exception.class)
    public Object unexpectedError(Exception error, HttpServletRequest request, RedirectAttributes flash) {
        log.error("Unable to process checkout", error);
        if ("GET".equals(request.getMethod())) {
            return new ModelAndView("checkout/load-error", HttpStatus.SERVICE_UNAVAILABLE);
        }
        flash.addFlashAttribute("errorMessage", "Chưa thể hoàn tất yêu cầu. Vui lòng thử lại; hệ thống sẽ kiểm tra đơn đã tạo để tránh đặt trùng.");
        return "redirect:/checkout";
    }
}
