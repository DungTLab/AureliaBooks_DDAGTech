package com.ddagtech.aureliabooks.repository.specification;

import com.ddagtech.aureliabooks.dto.request.OrderQueueFilter;
import com.ddagtech.aureliabooks.entity.Order;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;

public final class OrderQueueSpecification {
    private OrderQueueSpecification() {}

    public static Specification<Order> matching(OrderQueueFilter filter) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.notEqual(root.get("orderStatus"), Order.OrderStatus.PENDING_PAYMENT));
            if (filter.status() != null) predicates.add(cb.equal(root.get("orderStatus"), filter.status()));
            if (filter.paymentStatus() == OrderQueueFilter.PaymentFilter.REFUND_PENDING) {
                predicates.add(cb.equal(root.get("paymentStatus"), Order.PaymentStatus.PAID));
                predicates.add(root.get("orderStatus").in(Order.OrderStatus.CANCELLED, Order.OrderStatus.RETURNED));
            } else if (filter.paymentStatus() != null) {
                predicates.add(cb.equal(root.get("paymentStatus"), Order.PaymentStatus.valueOf(filter.paymentStatus().name())));
            }
            if (filter.paymentMethod() != null) predicates.add(cb.equal(root.get("paymentMethod"), filter.paymentMethod()));
            if (filter.fromDate() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.fromDate().atStartOfDay()));
            if (filter.toDate() != null) predicates.add(cb.lessThan(root.get("createdAt"), filter.toDate().plusDays(1).atStartOfDay()));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
