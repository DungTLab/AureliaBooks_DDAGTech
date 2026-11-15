package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.GoodsReceiptItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Spring Data JPA repository for {@link GoodsReceiptItem} entities (UC21, UC22, UC23).
 */
public interface GoodsReceiptItemRepository extends JpaRepository<GoodsReceiptItem, Long> {

    List<GoodsReceiptItem> findByReceiptId(Long receiptId);

    void deleteByReceiptId(Long receiptId);

    @Query("SELECT COUNT(i) > 0 FROM GoodsReceiptItem i WHERE i.product.id = :productId AND i.receipt.status = com.ddagtech.aureliabooks.entity.GoodsReceipt.Status.RECEIVED")
    boolean hasReceivedReceiptForProduct(@Param("productId") Long productId);
}
