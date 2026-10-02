package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.StockMovementLog;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.*;
import java.util.Optional;

public interface StockMovementLogRepository extends Repository<StockMovementLog, Long> {
    Optional<StockMovementLog> findById(Long id);
    Page<StockMovementLog> findAll(Pageable pageable);
    // TODO: controlled append belongs to the transactional service; no generic save/delete API.
}
