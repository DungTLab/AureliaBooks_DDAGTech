package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.GoodsReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {

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
