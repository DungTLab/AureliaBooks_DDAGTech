package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.CancelOrderRequest;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.dto.response.OrderTimelineStep;
import java.util.LinkedHashMap;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.CustomerOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@RequestMapping("/orders/my-orders")
public class CustomerOrderController {
    private final CustomerOrderService orders;
    private final CustomerIdentity identity;

    @GetMapping
    public String history(Authentication auth, @RequestParam(defaultValue = "0") int page,
                          @RequestParam(required = false) Order.OrderStatus status, Model model) {
        model.addAttribute("orders", orders.getMyOrders(identity.resolve(auth), page, status));
        model.addAttribute("selectedStatus", status);
        var statuses = new LinkedHashMap<Order.OrderStatus, String>();
        for (var value : Order.OrderStatus.values()) statuses.put(value, OrderTimelineStep.label(value));
        model.addAttribute("statusOptions", statuses);
        return "orders/my-orders";
    }

    @GetMapping("/{orderCode}")
    public String detail(Authentication auth, @PathVariable String orderCode, Model model) {
        model.addAttribute("detail", orders.getMyOrderDetail(identity.resolve(auth), orderCode));
        return "orders/detail";
    }

    @PostMapping("/{orderCode}/cancel")
    public String cancel(Authentication auth, @PathVariable String orderCode,
                         @Valid @ModelAttribute CancelOrderRequest request, BindingResult errors,
                         RedirectAttributes flash) {
        Long owner = identity.resolve(auth);
        // Check ownership even for an invalid form submission.
        orders.getMyOrderDetail(owner, orderCode);
        if (errors.hasErrors()) {
            flash.addFlashAttribute("errorMessage", errors.getFieldErrors().getFirst().getDefaultMessage());
        } else {
            try {
                orders.cancelMyOrder(owner, orderCode, request);
                flash.addFlashAttribute("successMessage", "Đơn hàng đã được hủy.");
            } catch (AppException exception) {
                if (exception.getErrorCode() == ErrorCode.ORDER_NOT_FOUND) throw exception;
                flash.addFlashAttribute("errorMessage", exception.getMessage());
            }
        }
        return "redirect:/orders/my-orders/" + orderCode;
    }

    @ExceptionHandler(AppException.class)
    public ModelAndView businessError(AppException exception) {
        ModelAndView result = new ModelAndView(exception.getErrorCode() == ErrorCode.ORDER_NOT_FOUND
                ? "error/404" : "orders/load-error");
        result.setStatus(exception.getErrorCode().getStatusCode());
        result.addObject("errorMessage", exception.getMessage());
        return result;
    }
}
