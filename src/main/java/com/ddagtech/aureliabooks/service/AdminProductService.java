package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC15/16. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending. */
public interface AdminProductService {
    Long createBook(BookForm form);
    void updateBook(Long productId, BookForm form);
    Long createStationery(StationeryForm form);
    void updateStationery(Long productId, StationeryForm form);
    void deactivate(Long productId);
}
