package com.ddagtech.aureliabooks.repository.specification;

import com.ddagtech.aureliabooks.entity.Product;
import org.springframework.data.jpa.domain.Specification;

/** FND-02. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
public abstract class ProductSpecification implements Specification<Product> {
    // TODO FND-02: Duy implements typed filter factories, predicates and joins.
    // Abstract by design: no always-true predicate pretending filtering is complete.
}
