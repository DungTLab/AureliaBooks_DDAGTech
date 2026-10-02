package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.ShippingAddress;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShippingAddressRepository extends JpaRepository<ShippingAddress, Long> {
    // TODO: owner adds only queries required by their Sprint 1 use cases.
}
