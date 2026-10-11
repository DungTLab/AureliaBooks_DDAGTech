package com.ddagtech.aureliabooks.dto.response;
import com.ddagtech.aureliabooks.entity.ShippingAddress;
public record AddressView(Long id, String recipientName, String phone,
        ShippingAddress.EconomicRegion economicRegion, String province, String district,
        String ward, String detailedAddress, boolean isDefault) {}
