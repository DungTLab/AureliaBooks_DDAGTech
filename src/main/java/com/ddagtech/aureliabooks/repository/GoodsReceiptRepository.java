package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.GoodsReceipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link GoodsReceipt} entities (UC21, UC22, UC23).
 */
public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT gr FROM GoodsReceipt gr WHERE gr.id = :id")
    Optional<GoodsReceipt> findByIdWithLock(@Param("id") Long id);

    Optional<GoodsReceipt> findByReceiptCode(String receiptCode);

    /**
     * Checks if any goods receipt exists referencing the specified supplier.
     * Enforces the UC30 rule: prohibit hard deletion of suppliers with existing receipts.
     */
    boolean existsBySupplierId(Long supplierId);

    /**
     * Counts the total number of goods receipts linked to a supplier.
     */
    long countBySupplierId(Long supplierId);

    boolean existsByReceiptCode(String receiptCode);

    Page<GoodsReceipt> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<GoodsReceipt> findByStatusOrderByCreatedAtDesc(GoodsReceipt.Status status, Pageable pageable);

    long countByStatus(GoodsReceipt.Status status);

    @EntityGraph(attributePaths = {"supplier", "createdBy", "receivedBy"})
    @Query(value = "SELECT gr FROM GoodsReceipt gr WHERE (:status IS NULL OR gr.status = :status) " +
                   "AND (:supplierId IS NULL OR gr.supplier.id = :supplierId)",
           countQuery = "SELECT COUNT(gr) FROM GoodsReceipt gr WHERE (:status IS NULL OR gr.status = :status) " +
                        "AND (:supplierId IS NULL OR gr.supplier.id = :supplierId)")
    Page<GoodsReceipt> findWithFilters(
            @Param("status") GoodsReceipt.Status status,
            @Param("supplierId") Long supplierId,
            Pageable pageable
    );
}
