package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC22/23/21. Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
public interface GoodsReceiptService {
    Long createDraft(Long staffId, GoodsReceiptForm form);
    void updateDraft(Long staffId, Long receiptId, GoodsReceiptForm form);
    void deleteDraft(Long staffId, Long receiptId);
    void receive(Long managerId, Long receiptId);
    Page<ProductSummary> lowStock(int threshold, Pageable pageable);
}
