package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.response.ReceiptSummary;
import com.ddagtech.aureliabooks.entity.GoodsReceipt;
import org.springframework.stereotype.Component;

/**
 * Stateless mapper for GoodsReceipt entity and DTO conversions (UC22/UC23).
 */
@Component
public class GoodsReceiptMapper {

    public ReceiptSummary toSummary(GoodsReceipt gr) {
        if (gr == null) {
            return null;
        }
        return new ReceiptSummary(
                gr.getId(),
                gr.getReceiptCode(),
                gr.getStatus() != null ? gr.getStatus().name() : null,
                gr.getTotalAmount()
        );
    }
}
