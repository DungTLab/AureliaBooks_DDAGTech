package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.ShippingAddress;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShippingAddressRepository extends JpaRepository<ShippingAddress, Long> {
    java.util.List<ShippingAddress> findByUserIdAndIsActiveTrueOrderByIdAsc(Long userId);
    java.util.Optional<ShippingAddress> findByIdAndUserIdAndIsActiveTrue(Long id, Long userId);
}
