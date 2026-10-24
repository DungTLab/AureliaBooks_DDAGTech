package com.ddagtech.aureliabooks.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelOrderRequest(
        @NotBlank(message = "Vui lòng nhập lý do hủy đơn.")
        @Size(max = 255, message = "Lý do hủy tối đa 255 ký tự.") String reason) {}
