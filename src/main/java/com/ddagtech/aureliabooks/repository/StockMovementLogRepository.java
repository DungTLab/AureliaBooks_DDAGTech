package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.StockMovementLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * FND-03 Append-Only Stock Movement Log Repository.
 * Extends basic Repository to prevent exposing generic save/update/delete APIs.
 * Only append (save) and read queries are supported (BR-01-04, BR-08-02).
 */
public interface StockMovementLogRepository extends Repository<StockMovementLog, Long> {

    // Controlled append: only save is exposed, no delete or update APIs
    StockMovementLog save(StockMovementLog log);

    Optional<StockMovementLog> findById(Long id);

    Page<StockMovementLog> findAll(Pageable pageable);

    @Query("SELECT s FROM StockMovementLog s JOIN FETCH s.product p LEFT JOIN FETCH s.performedBy u ORDER BY s.createdAt DESC")
    Page<StockMovementLog> findAllWithDetails(Pageable pageable);

    @Query("SELECT s FROM StockMovementLog s JOIN FETCH s.product p LEFT JOIN FETCH s.performedBy u WHERE s.product.id = :productId ORDER BY s.createdAt DESC")
    Page<StockMovementLog> findByProductId(@Param("productId") Long productId, Pageable pageable);

    @Query("SELECT s FROM StockMovementLog s JOIN FETCH s.product p LEFT JOIN FETCH s.performedBy u WHERE s.transactionType = :transactionType ORDER BY s.createdAt DESC")
    Page<StockMovementLog> findByTransactionType(@Param("transactionType") StockMovementLog.TransactionType transactionType, Pageable pageable);

    @Query("SELECT s FROM StockMovementLog s JOIN FETCH s.product p LEFT JOIN FETCH s.performedBy u WHERE s.referenceCode = :referenceCode ORDER BY s.createdAt DESC")
    List<StockMovementLog> findByReferenceCode(@Param("referenceCode") String referenceCode);

    @Query("SELECT s FROM StockMovementLog s JOIN FETCH s.product p LEFT JOIN FETCH s.performedBy u " +
           "WHERE (:transactionType IS NULL OR s.transactionType = :transactionType) " +
           "AND (:productId IS NULL OR s.product.id = :productId) " +
           "AND (:startDate IS NULL OR s.createdAt >= :startDate) " +
           "AND (:endDate IS NULL OR s.createdAt <= :endDate) " +
           "ORDER BY s.createdAt DESC")
    Page<StockMovementLog> findWithFilters(
            @Param("transactionType") StockMovementLog.TransactionType transactionType,
            @Param("productId") Long productId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);

    long count();
}
