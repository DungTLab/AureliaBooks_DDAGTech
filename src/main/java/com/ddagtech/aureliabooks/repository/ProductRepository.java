package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    // TODO: owner adds only queries required by their Sprint 1 use cases.
}
