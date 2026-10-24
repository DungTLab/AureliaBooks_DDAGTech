package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.math.BigDecimal;
import java.time.LocalDate;

public record RefundConfirmationRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 100) String method,
        @NotNull @PastOrPresent @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate completedDate,
        @Size(max = 100) String reference,
        @Size(max = 1000) String supportingInfo,
        @AssertTrue(message = "Phải xác nhận đã thực sự hoàn tiền bên ngoài hệ thống.")
        @NotNull Boolean externallyCompleted) {}
