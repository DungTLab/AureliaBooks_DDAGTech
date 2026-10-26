package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.AddressRequest;
import com.ddagtech.aureliabooks.dto.response.AddressView;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.ShippingAddressMapper;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.AddressService;
import com.ddagtech.aureliabooks.util.DeliveryProvinceCatalog;
import jakarta.validation.Validator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    private final ShippingAddressRepository addresses;
    private final UserRepository users;
    private final AddressOrderGuard orders;
    private final Validator validator;
    private final ShippingAddressMapper addressMapper;


    @Transactional(readOnly = true)
    public List<AddressView> list(Long ownerId) {
        CustomerIdentity.requireCustomer(
            users.findById(ownerId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED))
        );
        return active(ownerId).stream().map(addressMapper::toView).toList();
    }

    @Transactional
    public Long create(Long ownerId, AddressRequest request) {
        var province = validate(request);
        User owner = lockOwner(ownerId);
        var all = active(ownerId);
        if (all.size() >= 5) throw new AppException(ErrorCode.ADDRESS_QUOTA_EXCEEDED);
        boolean isDefault = all.isEmpty() || Boolean.TRUE.equals(request.isDefault());
        ShippingAddress a = addressMapper.toEntity(request, owner, isDefault);
        applyLocation(a, province);
        if (Boolean.TRUE.equals(a.getIsDefault())) all.forEach(existing -> existing.setIsDefault(false));
        all.add(a);
        normalizeDefault(all);
        addresses.saveAllAndFlush(all);
        return a.getId();
    }

    @Transactional
    public void update(Long ownerId, Long id, AddressRequest request) {
        var province = validate(request);
        lockOwner(ownerId);
        ShippingAddress a = owned(ownerId, id);
        addressMapper.updateEntity(a, request);
        applyLocation(a, province);
        var all = active(ownerId);
        // A selected default cannot be unset without choosing another address.
        if (Boolean.TRUE.equals(request.isDefault())) all.forEach(existing ->
            existing.setIsDefault(existing.getId().equals(id))
        );
        normalizeDefault(all);
        addresses.saveAllAndFlush(all);
    }

    @Transactional
    public void setDefault(Long ownerId, Long id) {
        lockOwner(ownerId);
        owned(ownerId, id);
        var all = active(ownerId);
        all.forEach(a -> a.setIsDefault(a.getId().equals(id)));
        addresses.saveAllAndFlush(all);
    }

    @Transactional
    public void delete(Long ownerId, Long id) {
        lockOwner(ownerId);
        ShippingAddress a = owned(ownerId, id);
        if (orders.hasUnfinishedOrder(ownerId, id)) throw new AppException(ErrorCode.ADDRESS_LOCKED_ACTIVE_ORDER);
        a.setIsActive(false);
        a.setIsDefault(false);
        addresses.saveAndFlush(a);
        var remaining = active(ownerId);
        normalizeDefault(remaining);
        addresses.saveAllAndFlush(remaining);
    }

    private User lockOwner(Long id) {
        User u = users.findOwnerForUpdate(id).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        CustomerIdentity.requireCustomer(u);
        return u;
    }

    private ShippingAddress owned(Long owner, Long id) {
        return addresses
            .findByIdAndUserIdAndIsActiveTrue(id, owner)
            .orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND));
    }

    private List<ShippingAddress> active(Long owner) {
        return new java.util.ArrayList<>(addresses.findByUserIdAndIsActiveTrueOrderByIdAsc(owner));
    }

    private void normalizeDefault(List<ShippingAddress> all) {
        ShippingAddress chosen = all
            .stream()
            .filter(a -> Boolean.TRUE.equals(a.getIsDefault()))
            .findFirst()
            .orElse(all.isEmpty() ? null : all.get(0));
        all.forEach(a -> a.setIsDefault(a == chosen));
    }

    private void applyLocation(ShippingAddress address, DeliveryProvinceCatalog.Province province) {
        address.setProvince(province.name());
        address.setEconomicRegion(province.region());
    }

    private DeliveryProvinceCatalog.Province validate(AddressRequest r) {
        var violations = validator.validate(r);
        if (!violations.isEmpty()) throw new AppException(
            ErrorCode.INVALID_INPUT_DATA,
            violations.iterator().next().getMessage()
        );
        return DeliveryProvinceCatalog.findByCode(r.provinceCode()).orElseThrow(() ->
            new AppException(ErrorCode.INVALID_INPUT_DATA, "Vui lòng chọn tỉnh/thành phố trong danh sách hiện hành.")
        );
    }
}
