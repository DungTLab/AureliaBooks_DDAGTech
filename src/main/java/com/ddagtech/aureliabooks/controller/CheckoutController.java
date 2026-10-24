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
import com.ddagtech.aureliabooks.util.DeliveryProvinceCatalog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Locale;
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

/** Coordinate UC11: select delivery details, review server-side data, place the order, and display the result. */
@Slf4j
@Controller
@RequiredArgsConstructor
public class CheckoutController {

    private final OrderPlacementService orders;
    private final AddressService addresses;
    private final CustomerIdentity identity;
    private final CheckoutSessionStore reviews;
    private final ObjectProvider<CheckoutPaymentGateway> payments;

    /** Load the cart and addresses for display; opening this page does not create an order or reserve stock. */
    @GetMapping("/checkout")
    public String checkout(
        Authentication auth,
        @RequestParam(required = false) Long shippingAddressId,
        @RequestParam(defaultValue = "COD") Order.PaymentMethod paymentMethod,
        @RequestParam(required = false) String voucherCode,
        Model model
    ) {
        Long owner = identity.resolve(auth);
        CheckoutView checkout;
        String code = normalizeVoucher(voucherCode);
        try {
            checkout = loadCheckout(owner, shippingAddressId, code);
            model.addAttribute("appliedVoucherCode", code);
        } catch (AppException error) {
            if (code.isEmpty() || !error.getErrorCode().name().startsWith("VOUCHER_")) throw error;
            checkout = orders.getCheckoutView(owner, shippingAddressId);
            model.addAttribute("errorMessage", error.getMessage());
        }
        model.addAttribute("voucherCode", code);
        addCheckout(model, checkout, paymentMethod);
        if (!model.containsAttribute("addressForm")) {
            model.addAttribute("addressForm", new AddressRequest(null, null, null, null, null, false));
        }
        return "checkout/view";
    }

    /** Validate selections and save the review in the session; never accept totals from the form. */
    @PostMapping("/checkout/review")
    public String prepare(
        Authentication auth,
        @Valid @ModelAttribute("selection") CheckoutSelectionRequest selection,
        BindingResult errors,
        HttpServletRequest request,
        RedirectAttributes flash
    ) {
        Long owner = identity.resolve(auth);
        if (errors.hasErrors()) return invalidForm(errors, flash);
        if (selection.paymentMethod() == Order.PaymentMethod.VNPAY && !paymentAvailable()) {
            flash.addFlashAttribute("errorMessage", "Thanh toán trực tuyến tạm thời chưa khả dụng. Vui lòng chọn COD.");
            return "redirect:/checkout";
        }
        CheckoutView view = loadCheckout(
            owner,
            selection.shippingAddressId(),
            normalizeVoucher(selection.voucherCode())
        );
        if (hasInvalidItems(view)) {
            flash.addFlashAttribute("errorMessage", "Một số sản phẩm cần được cập nhật. Vui lòng kiểm tra giỏ hàng.");
            return "redirect:/cart";
        }
        CheckoutConfirmation review = reviews.save(
            request.getSession(),
            owner,
            view,
            selection.paymentMethod(),
            normalizeVoucher(selection.voucherCode()).isEmpty() ? null : normalizeVoucher(selection.voucherCode())
        );
        return "redirect:/checkout/review/" + review.checkoutToken();
    }

    /** Display the saved review for its token and owner without silently replacing it with current prices. */
    @GetMapping("/checkout/review/{token}")
    public String review(Authentication auth, @PathVariable String token, HttpServletRequest request, Model model) {
        Long owner = identity.resolve(auth);
        CheckoutConfirmation review = reviews.find(request.getSession(false), token, owner);
        if (review == null) throw new AppException(
            ErrorCode.CHECKOUT_CHANGED,
            "Phiên xác nhận đã hết hiệu lực. Vui lòng kiểm tra lại đơn hàng."
        );
        model.addAttribute("review", review);
        model.addAttribute("checkout", review.reviewedCheckout());
        model.addAttribute(
            "delivery",
            review
                .reviewedCheckout()
                .addressViews()
                .stream()
                .filter(address -> address.id().equals(review.reviewedCheckout().selectedAddressId()))
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND))
        );
        model.addAttribute("onlinePaymentAvailable", paymentAvailable());
        return "checkout/review";
    }

    /** Pass the request and session review to the service, which owns the transaction and duplicate protection. */
    @PostMapping("/checkout/place")
    public String place(
        Authentication auth,
        @Valid @ModelAttribute("checkoutForm") CheckoutRequest form,
        BindingResult errors,
        HttpServletRequest request,
        RedirectAttributes flash
    ) {
        // Resolve the owner from the authenticated account rather than a browser-supplied userId.
        Long owner = identity.resolve(auth);
        if (errors.hasErrors()) return invalidForm(errors, flash);
        CheckoutConfirmation review = reviews.find(request.getSession(false), form.getCheckoutToken(), owner);
        // Reject VNPay before creating an order when payment handoff is unavailable.
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

    /** Display only the current customer's order using persisted snapshots and statuses. */
    @GetMapping("/orders/{orderCode}")
    public String confirmation(Authentication auth, @PathVariable String orderCode, Model model) {
        OrderConfirmationView order = orders.getOrderConfirmation(identity.resolve(auth), orderCode);
        model.addAttribute("order", order);
        model.addAttribute("onlinePaymentAvailable", paymentAvailable());
        return "checkout/order-confirm";
    }

    /** Retry payment for an existing pending VNPay order without creating another order or deducting stock again. */
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

    /** Delegate address creation to UC09, save it in the account, and select it on checkout. */
    @PostMapping("/checkout/addresses")
    public String addAddress(
        Authentication auth,
        @Valid @ModelAttribute("addressForm") AddressRequest form,
        BindingResult errors,
        Model model,
        RedirectAttributes flash
    ) {
        Long owner = identity.resolve(auth);
        if (
            !errors.hasFieldErrors("provinceCode") && DeliveryProvinceCatalog.findByCode(form.provinceCode()).isEmpty()
        ) {
            errors.rejectValue(
                "provinceCode",
                "invalidProvince",
                "Vui lòng chọn tỉnh/thành phố trong danh sách hiện hành."
            );
        }
        if (errors.hasErrors()) {
            addCheckout(model, orders.getCheckoutView(owner, null), Order.PaymentMethod.COD);
            model.addAttribute("addressFormOpen", true);
            return "checkout/view";
        }
        Long addressId = addresses.create(owner, form);
        flash.addFlashAttribute("successMessage", "Đã lưu địa chỉ giao hàng.");
        return "redirect:/checkout?shippingAddressId=" + addressId;
    }

    private CheckoutView loadCheckout(Long owner, Long addressId, String code) {
        return code.isEmpty()
            ? orders.getCheckoutView(owner, addressId)
            : orders.getCheckoutView(owner, addressId, code);
    }

    private String normalizeVoucher(String code) {
        return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    }

    /** Share display attributes between the checkout page and address forms returned with validation errors. */
    private void addCheckout(Model model, CheckoutView view, Order.PaymentMethod method) {
        model.addAttribute("provinces", DeliveryProvinceCatalog.all());
        model.addAttribute("checkout", view);
        model.addAttribute("selectedPaymentMethod", method);
        model.addAttribute("onlinePaymentAvailable", paymentAvailable());
        model.addAttribute("itemsReady", !hasInvalidItems(view));
    }

    /** Require cart updates for warnings, unavailable products, or quantities exceeding available stock. */
    private boolean hasInvalidItems(CheckoutView view) {
        return view
            .items()
            .stream()
            .anyMatch(
                item ->
                    item.warningMessage() != null || !item.availableForSale() || item.quantity() > item.availableStock()
            );
    }

    /** Disable VNPay in both the UI and POST handlers when the UC11.1 adapter is missing or unconfigured. */
    private boolean paymentAvailable() {
        CheckoutPaymentGateway gateway = payments.getIfAvailable();
        return gateway != null && gateway.isAvailable();
    }

    /** Start payment after the order transaction commits; keep the order available for retry if handoff fails. */
    private String startPayment(Long owner, String code, RedirectAttributes flash) {
        try {
            CheckoutPaymentGateway gateway = payments.getIfAvailable();
            if (gateway == null || !gateway.isAvailable()) throw new IllegalStateException("Payment unavailable");
            // The adapter must return a valid HTTPS destination before the controller redirects the customer.
            URI destination = URI.create(gateway.startPayment(owner, code));
            if (
                !"https".equalsIgnoreCase(destination.getScheme()) ||
                destination.getHost() == null ||
                destination.getUserInfo() != null
            ) throw new IllegalStateException("Invalid payment destination");
            return "redirect:" + destination;
        } catch (RuntimeException error) {
            log.error("Unable to start payment for committed order {}", code, error);
            flash.addFlashAttribute(
                "errorMessage",
                "Đơn hàng đã được tạo nhưng chưa thể mở cổng thanh toán. Bạn có thể thử lại từ trang đơn hàng."
            );
            return "redirect:/orders/" + code;
        }
    }

    /** Return readable validation errors to checkout without invoking order placement. */
    private String invalidForm(BindingResult errors, RedirectAttributes flash) {
        var error = errors.getAllErrors().getFirst();
        flash.addFlashAttribute(
            "errorMessage",
            error.getCode() != null && error.getCode().startsWith("typeMismatch")
                ? "Thông tin đặt hàng không hợp lệ. Vui lòng kiểm tra lại."
                : error.getDefaultMessage()
        );
        return "redirect:/checkout";
    }

    /** Route business errors while protecting order ownership, handling expired sessions, and requiring a new review after changes. */
    @ExceptionHandler(AppException.class)
    public Object businessError(
        AppException error,
        HttpServletRequest request,
        HttpServletResponse response,
        Authentication auth,
        RedirectAttributes flash
    ) {
        ErrorCode code = error.getErrorCode();
        if (code == ErrorCode.ORDER_NOT_FOUND) {
            return new ModelAndView("error/404", HttpStatus.NOT_FOUND);
        }
        flash.addFlashAttribute("errorMessage", error.getMessage());
        if (
            code == ErrorCode.UNAUTHENTICATED || code == ErrorCode.ACCOUNT_LOCKED || code == ErrorCode.USER_NOT_EXISTED
        ) {
            new SecurityContextLogoutHandler().logout(request, response, auth);
            return "redirect:/auth/login";
        }
        if (code == ErrorCode.UNAUTHORIZED) return "redirect:/error/403";
        if (code == ErrorCode.CHECKOUT_CHANGED) {
            reviews.remove(request.getSession(false), request.getParameter("checkoutToken"));
        }
        if (
            code == ErrorCode.CART_EMPTY || code == ErrorCode.INSUFFICIENT_STOCK || code == ErrorCode.PRODUCT_NOT_FOUND
        ) return "redirect:/cart";
        return "redirect:/checkout";
    }

    /** Handle URL or form parameters that cannot be converted to an ID or payment method. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public String invalidParameters(RedirectAttributes flash) {
        flash.addFlashAttribute("errorMessage", "Địa chỉ hoặc phương thức thanh toán không hợp lệ.");
        return "redirect:/checkout";
    }

    /** Return HTTP 503 for failed reads; failed submissions can be retried using the token to identify an existing order. */
    @ExceptionHandler(Exception.class)
    public Object unexpectedError(Exception error, HttpServletRequest request, RedirectAttributes flash) {
        log.error("Unable to process checkout", error);
        if ("GET".equals(request.getMethod())) {
            return new ModelAndView("checkout/load-error", HttpStatus.SERVICE_UNAVAILABLE);
        }
        flash.addFlashAttribute(
            "errorMessage",
            "Chưa thể hoàn tất yêu cầu. Vui lòng thử lại; hệ thống sẽ kiểm tra đơn đã tạo để tránh đặt trùng."
        );
        return "redirect:/checkout";
    }
}
