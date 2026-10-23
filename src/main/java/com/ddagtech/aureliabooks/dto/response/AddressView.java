package com.ddagtech.aureliabooks.dto.response;
import com.ddagtech.aureliabooks.entity.ShippingAddress;
public record AddressView(Long id, String recipientName, String phone,
        ShippingAddress.EconomicRegion economicRegion, String province, String district,
        String ward, String detailedAddress, boolean isDefault) {
    public String fullAddress() {
        return java.util.stream.Stream.of(detailedAddress, ward, district, province)
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
