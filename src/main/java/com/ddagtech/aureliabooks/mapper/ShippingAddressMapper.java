package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.request.AddressRequest;
import com.ddagtech.aureliabooks.dto.response.AddressView;
import com.ddagtech.aureliabooks.entity.ShippingAddress;
import com.ddagtech.aureliabooks.entity.User;
import org.springframework.stereotype.Component;

/**
 * Stateless mapper for ShippingAddress entity and DTO conversions (UC08).
 */
@Component
public class ShippingAddressMapper {

    public ShippingAddress toEntity(AddressRequest req, User owner, boolean isDefault) {
        if (req == null) {
            return null;
        }
        ShippingAddress entity = new ShippingAddress();
        entity.setUser(owner);
        updateEntity(entity, req);
        entity.setIsDefault(isDefault);
        entity.setIsActive(true);
        return entity;
    }

    public void updateEntity(ShippingAddress target, AddressRequest req) {
        if (target == null || req == null) {
            return;
        }
        target.setRecipientName(req.recipientName().strip());
        target.setPhone(req.phone().strip());
        // Province and shipping zone are assigned by the service from trusted reference data.
        target.setDistrict(null);
        target.setWard(req.ward().strip());
        target.setDetailedAddress(req.detailedAddress().strip());
    }

    public AddressView toView(ShippingAddress a) {
        if (a == null) {
            return null;
        }
        return new AddressView(
            a.getId(),
            a.getRecipientName(),
            a.getPhone(),
            a.getEconomicRegion(),
            a.getProvince(),
            a.getDistrict(),
            a.getWard(),
            a.getDetailedAddress(),
            Boolean.TRUE.equals(a.getIsDefault())
        );
    }
}
