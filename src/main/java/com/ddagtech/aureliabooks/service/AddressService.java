package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC09. Owner: Lê Tiến Dũng. Sprint 1 scaffold; business implementation pending. */
public interface AddressService {
    Long create(Long authenticatedUserId, AddressRequest request);
    void update(Long authenticatedUserId, Long addressId, AddressRequest request);
    void delete(Long authenticatedUserId, Long addressId);
    void setDefault(Long authenticatedUserId, Long addressId);
}
