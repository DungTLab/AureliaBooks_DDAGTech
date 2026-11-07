package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);
    @Query("select distinct item.product.id from OrderItem item where item.order.user.id = :customerId and item.order.orderStatus = 'DELIVERED'")
    List<Long> findDeliveredProductIds(@Param("customerId") Long customerId);
}
