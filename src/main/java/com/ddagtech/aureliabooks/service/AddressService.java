package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.dto.response.*;
import org.springframework.data.domain.*;

/** UC09 address book; implementations enforce ownership and serialize mutations per customer. */
public interface AddressService {
    java.util.List<AddressView> list(Long authenticatedUserId);
    Long create(Long authenticatedUserId, AddressRequest request);
    void update(Long authenticatedUserId, Long addressId, AddressRequest request);
    void delete(Long authenticatedUserId, Long addressId);
    void setDefault(Long authenticatedUserId, Long addressId);
}
