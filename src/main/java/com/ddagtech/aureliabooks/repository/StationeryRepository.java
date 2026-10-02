package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Stationery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationeryRepository extends JpaRepository<Stationery, Long> {
    // TODO: owner adds only queries required by their Sprint 1 use cases.
}
