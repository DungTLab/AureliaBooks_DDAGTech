package com.ddagtech.aureliabooks.controller;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.OrderTimelineStep;
import com.ddagtech.aureliabooks.entity.Order;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.security.StaffIdentity;
import com.ddagtech.aureliabooks.service.OrderStateMachineService;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.DataAccessException;
import jakarta.persistence.PersistenceException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.LinkedHashMap;

@Controller
@RequiredArgsConstructor
@RequestMapping("/staff/orders")
@PreAuthorize("hasAnyRole('SALE_STAFF', 'MANAGER')")
public class StaffOrderController {
    private final OrderStateMachineService orders;
    private final StaffIdentity identity;

    @GetMapping
    public String queue(Authentication auth, @ModelAttribute("filter") OrderQueueFilter filter,
                        BindingResult errors, @RequestParam(defaultValue = "0") int page, Model model) {
        Long actor = identity.resolve(auth);
        if (errors.hasErrors()) throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Bộ lọc không hợp lệ. Vui lòng kiểm tra trạng thái và ngày đặt.");
        model.addAttribute("orders", orders.getOrderQueue(filter, PageRequest.of(Math.max(0, page), 20), actor));
        var statuses = new LinkedHashMap<Order.OrderStatus, String>();
        for (var status : Order.OrderStatus.values())
            if (status != Order.OrderStatus.PENDING_PAYMENT) statuses.put(status, OrderTimelineStep.label(status));
        model.addAttribute("statusOptions", statuses);
        return "staff/staff-orders";
    }

    @GetMapping("/{id}")
    public String checklist(Authentication auth, @PathVariable Long id, Model model) {
        model.addAttribute("refundToday", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
        model.addAttribute("checklist", orders.getPackagingChecklist(id, identity.resolve(auth)));
        return "staff/packaging-checklist";
    }

    @PostMapping("/{id}/transition")
    public Object transition(Authentication auth, @PathVariable Long id,
                             @Valid @ModelAttribute OrderTransitionRequest request, BindingResult errors,
                             RedirectAttributes flash) {
        Long actor = identity.resolve(auth);
        if (errors.hasErrors()) throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                errors.getAllErrors().getFirst().getDefaultMessage());
        try {
            orders.transitionStatus(id, request, actor);
        } catch (AppException exception) {
            if (exception.getErrorCode().getStatusCode() == HttpStatus.CONFLICT)
                return latest(id, actor, exception.getMessage(), HttpStatus.CONFLICT);
            throw exception;
        }
        flash.addFlashAttribute("successMessage", "Đã cập nhật trạng thái đơn hàng.");
        return "redirect:/staff/orders/" + id;
    }

    @PostMapping("/{id}/return-restock")
    @PreAuthorize("hasRole('SALE_STAFF')")
    public Object returnRestock(Authentication auth, @PathVariable Long id,
                                @Valid @ModelAttribute ReturnConfirmationRequest request, BindingResult errors,
                                RedirectAttributes flash) {
        Long actor = identity.resolve(auth);
        if (errors.hasErrors()) {
            return latest(id, actor,
                    errors.getAllErrors().getFirst().getDefaultMessage(), HttpStatus.BAD_REQUEST);
        }
        try {
            orders.confirmReturnRestock(id, request, actor);
        } catch (AppException exception) {
            if (exception.getErrorCode() == ErrorCode.UNAUTHORIZED || exception.getErrorCode() == ErrorCode.ORDER_NOT_FOUND) {
                throw exception;
            }
            return latest(id, actor, exception.getMessage(), exception.getErrorCode().getStatusCode());
        }
        flash.addFlashAttribute("successMessage", "Đã xác nhận nhận lại toàn bộ hàng hoàn và hoàn kho thành công.");
        return "redirect:/staff/orders/" + id;
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasRole('SALE_STAFF')")
    public Object refund(Authentication auth, @PathVariable Long id,
                         @Valid @ModelAttribute RefundConfirmationRequest request, BindingResult errors,
                         RedirectAttributes flash) {
        Long actor = identity.resolve(auth);
        if (errors.hasErrors()) return latest(id, actor,
                "Thông tin hoàn tiền không hợp lệ. Kiểm tra số tiền, ngày hoàn, chứng từ và xác nhận đã hoàn tiền.", HttpStatus.BAD_REQUEST);
        try {
            orders.confirmExternalRefund(id, request, actor);
        } catch (AppException exception) {
            if (exception.getErrorCode() == ErrorCode.UNAUTHORIZED || exception.getErrorCode() == ErrorCode.ORDER_NOT_FOUND) throw exception;
            return latest(id, actor, exception.getMessage(), exception.getErrorCode().getStatusCode());
        }
        flash.addFlashAttribute("successMessage", "Đã ghi nhận hoàn tiền bên ngoài hệ thống. Tồn kho không thay đổi.");
        return "redirect:/staff/orders/" + id;
    }

    private ModelAndView latest(Long id, Long actor, String message, HttpStatus status) {
        var result = new ModelAndView("staff/packaging-checklist");
        result.addObject("refundToday", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
        result.addObject("checklist", orders.getPackagingChecklist(id, actor));
        result.addObject("errorMessage", message);
        result.setStatus(status);
        return result;
    }

    @ExceptionHandler({DataAccessException.class, PersistenceException.class})
    public ModelAndView retrievalOrUpdateFailure(Exception exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Không thể tải hoặc lưu thông tin đơn hàng. Vui lòng thử lại; không có kết quả thành công nào được xác nhận.");
    }

    @ExceptionHandler(AppException.class)
    public ModelAndView businessError(AppException exception) {
        return error(exception.getErrorCode().getStatusCode(), exception.getMessage());
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, PessimisticLockException.class, LockTimeoutException.class})
    public ModelAndView concurrentUpdate(Exception exception) {
        return error(HttpStatus.CONFLICT, "Đơn hàng đang được xử lý bởi một thao tác khác. Vui lòng tải lại đơn và thử lại.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ModelAndView invalidParameter(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "Tham số không hợp lệ.");
    }

    private ModelAndView error(HttpStatus status, String message) {
        var result = new ModelAndView("staff/order-error");
        result.setStatus(status);
        result.addObject("errorMessage", message);
        return result;
    }
}
