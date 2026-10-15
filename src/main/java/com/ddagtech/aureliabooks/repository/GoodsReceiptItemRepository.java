package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.GoodsReceiptItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoodsReceiptItemRepository extends JpaRepository<GoodsReceiptItem, Long> {

    List<GoodsReceiptItem> findByReceiptId(Long receiptId);

    void deleteByReceiptId(Long receiptId);

    @Query("SELECT COUNT(i) > 0 FROM GoodsReceiptItem i WHERE i.product.id = :productId AND i.receipt.status = com.ddagtech.aureliabooks.entity.GoodsReceipt.Status.RECEIVED")
    boolean hasReceivedReceiptForProduct(@Param("productId") Long productId);
}
