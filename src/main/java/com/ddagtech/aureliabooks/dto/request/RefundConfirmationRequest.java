package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

public record RefundConfirmationRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "Tiền mặt|Chuyển khoản") String method,
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate completedDate,
        @NotBlank @Size(max = 1000) String supportingInfo,
        @AssertTrue(message = "Phải xác nhận đã thực sự hoàn tiền bên ngoài hệ thống.")
        @NotNull Boolean externallyCompleted) {

    // Use the same Vietnam calendar as the refund form; @NotNull handles a missing date.
    @AssertTrue(message = "Ngày hoàn tiền không được sau ngày hiện tại tại Việt Nam.")
    public boolean isCompletedDateValid() {
        return completedDate == null
                || !completedDate.isAfter(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
    }
}
