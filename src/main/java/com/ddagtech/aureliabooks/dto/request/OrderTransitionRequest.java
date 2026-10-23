package com.ddagtech.aureliabooks.dto.request;

import com.ddagtech.aureliabooks.entity.Order;
import jakarta.validation.constraints.*;

public record OrderTransitionRequest(
        @NotNull(message = "Vui lòng chọn trạng thái đích.") Order.OrderStatus targetStatus,
        @Size(max = 255, message = "Lý do hủy tối đa 255 ký tự.") String reason,
        @Size(max = 100, message = "Mã vận đơn tối đa 100 ký tự.") String trackingNumber,
        Boolean codCollected) {}
