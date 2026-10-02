package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<Brand, Long> {
    // TODO: owner adds only queries required by their Sprint 1 use cases.
}
