package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.GoodsReceipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT gr FROM GoodsReceipt gr WHERE gr.id = :id")
    Optional<GoodsReceipt> findByIdWithLock(@Param("id") Long id);

    Optional<GoodsReceipt> findByReceiptCode(String receiptCode);

    /**
     * Checks if any goods receipt exists referencing the specified supplier.
     * Used to enforce UC30 business rule: prohibit hard deletion of suppliers with receipts.
     */
    boolean existsBySupplierId(Long supplierId);

    /**
     * Counts the total number of goods receipts linked to a supplier.
     */
    long countBySupplierId(Long supplierId);
}
