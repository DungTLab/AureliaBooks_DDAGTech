package com.ddagtech.aureliabooks.service.impl;
import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.AddressRequest;
import com.ddagtech.aureliabooks.dto.response.AddressView;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.security.CustomerIdentity;
import com.ddagtech.aureliabooks.service.AddressService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service @RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {
    private final ShippingAddressRepository addresses;
    private final UserRepository users;
    private final AddressOrderGuard orders;
    private final Validator validator;
    @Transactional(readOnly = true)
    public List<AddressView> list(Long ownerId) {
        CustomerIdentity.requireCustomer(users.findById(ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED)));
        return active(ownerId).stream().map(a -> new AddressView(a.getId(), a.getRecipientName(), a.getPhone(),
                a.getEconomicRegion(), a.getProvince(), a.getDistrict(), a.getWard(), a.getDetailedAddress(),
                Boolean.TRUE.equals(a.getIsDefault()))).toList();
    }
    @Transactional
    public Long create(Long ownerId, AddressRequest request) {
        validate(request); User owner = lockOwner(ownerId); var all = active(ownerId);
        if (all.size() >= 5) throw new AppException(ErrorCode.ADDRESS_QUOTA_EXCEEDED);
        ShippingAddress a = new ShippingAddress(); a.setUser(owner); copy(a, request);
        a.setIsDefault(all.isEmpty() || Boolean.TRUE.equals(request.isDefault()));
        if (Boolean.TRUE.equals(a.getIsDefault())) all.forEach(existing -> existing.setIsDefault(false));
        all.add(a); normalizeDefault(all); addresses.saveAllAndFlush(all); return a.getId();
    }
    @Transactional
    public void update(Long ownerId, Long id, AddressRequest request) {
        validate(request); lockOwner(ownerId); ShippingAddress a = owned(ownerId, id);
        copy(a, request); var all = active(ownerId);
        // A selected default cannot be unset without choosing another address.
        if (Boolean.TRUE.equals(request.isDefault())) all.forEach(existing -> existing.setIsDefault(existing.getId().equals(id)));
        normalizeDefault(all); addresses.saveAllAndFlush(all);
    }
    @Transactional
    public void setDefault(Long ownerId, Long id) {
        lockOwner(ownerId); owned(ownerId, id); var all = active(ownerId);
        all.forEach(a -> a.setIsDefault(a.getId().equals(id))); addresses.saveAllAndFlush(all);
    }
    @Transactional
    public void delete(Long ownerId, Long id) {
        lockOwner(ownerId); ShippingAddress a = owned(ownerId, id);
        if (orders.hasUnfinishedOrder(ownerId, id)) throw new AppException(ErrorCode.ADDRESS_LOCKED_ACTIVE_ORDER);
        a.setIsActive(false); a.setIsDefault(false); addresses.saveAndFlush(a);
        var remaining = active(ownerId); normalizeDefault(remaining); addresses.saveAllAndFlush(remaining);
    }
    private User lockOwner(Long id) {
        User u = users.findOwnerForUpdate(id).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        CustomerIdentity.requireCustomer(u); return u;
    }
    private ShippingAddress owned(Long owner, Long id) {
        return addresses.findByIdAndUserIdAndIsActiveTrue(id, owner)
                .orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND));
    }
    private List<ShippingAddress> active(Long owner) {
        return new java.util.ArrayList<>(addresses.findByUserIdAndIsActiveTrueOrderByIdAsc(owner));
    }
    private void normalizeDefault(List<ShippingAddress> all) {
        ShippingAddress chosen = all.stream().filter(a -> Boolean.TRUE.equals(a.getIsDefault())).findFirst()
                .orElse(all.isEmpty() ? null : all.get(0));
        all.forEach(a -> a.setIsDefault(a == chosen));
    }
    private void copy(ShippingAddress a, AddressRequest r) {
        a.setRecipientName(r.recipientName().strip()); a.setPhone(r.phone()); a.setEconomicRegion(r.economicRegion());
        a.setProvince(r.province().strip()); a.setDistrict(r.district().strip()); a.setWard(r.ward().strip());
        a.setDetailedAddress(r.detailedAddress().strip());
    }
    private void validate(AddressRequest r) {
        var violations = validator.validate(r);
        if (!violations.isEmpty()) throw new AppException(ErrorCode.INVALID_INPUT_DATA, violations.iterator().next().getMessage());
    }
}
