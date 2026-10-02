package com.ddagtech.aureliabooks.dto.request;

import com.ddagtech.aureliabooks.entity.ShippingAddress;

/** UC09: owner resolved from authenticated principal. Owner: Lê Tiến Dũng. Sprint 1 scaffold; business implementation pending. */
public record AddressRequest(
        String recipientName,
        String phone,
        ShippingAddress.EconomicRegion economicRegion,
        String province,
        String district,
        String ward,
        String detailedAddress,
        Boolean isDefault) {
    // TODO: field validation and business validation are owned by the assigned developer.
}
