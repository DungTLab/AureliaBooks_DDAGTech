package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
    // TODO: owner adds only queries required by their Sprint 1 use cases.
}
