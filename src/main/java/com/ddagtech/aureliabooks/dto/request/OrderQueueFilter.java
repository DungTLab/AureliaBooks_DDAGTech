package com.ddagtech.aureliabooks.dto.request;

import com.ddagtech.aureliabooks.entity.Order;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;

public record OrderQueueFilter(Order.OrderStatus status, Order.PaymentStatus paymentStatus,
                               Order.PaymentMethod paymentMethod,
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {}
