package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC30. Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
public interface SupplierService {
    Long create(SupplierForm form);
    void update(Long supplierId, SupplierForm form);
    void deactivate(Long supplierId);
}
