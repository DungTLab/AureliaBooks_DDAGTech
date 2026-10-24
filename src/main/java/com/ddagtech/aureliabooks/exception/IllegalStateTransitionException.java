package com.ddagtech.aureliabooks.exception;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.entity.Order.OrderStatus;

public class IllegalStateTransitionException extends AppException {
    public IllegalStateTransitionException(OrderStatus current, OrderStatus target) {
        super(ErrorCode.INVALID_ORDER_STATE_TRANSITION,
                "Không thể chuyển đơn hàng từ " + current + " sang " + target + ".");
    }
}
