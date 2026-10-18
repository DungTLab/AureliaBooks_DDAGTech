package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.request.SupplierForm;
import com.ddagtech.aureliabooks.dto.response.SupplierSummary;
import com.ddagtech.aureliabooks.entity.Supplier;
import org.springframework.stereotype.Component;

/**
 * Stateless mapper for Supplier entity and DTO conversions (UC30).
 */
@Component
public class SupplierMapper {

    public Supplier toEntity(SupplierForm form) {
        if (form == null) {
            return null;
        }
        return Supplier.builder()
                .name(form.name() != null ? form.name().trim() : null)
                .contactName(form.contactName() != null ? form.contactName().trim() : null)
                .phone(form.phone() != null ? form.phone().trim() : null)
                .email(form.email() != null ? form.email().trim() : null)
                .address(form.address() != null ? form.address().trim() : null)
                .isActive(form.isActive() != null ? form.isActive() : true)
                .build();
    }

    public void updateEntity(Supplier target, SupplierForm form) {
        if (target == null || form == null) {
            return;
        }
        target.setName(form.name() != null ? form.name().trim() : null);
        target.setContactName(form.contactName() != null ? form.contactName().trim() : null);
        target.setPhone(form.phone() != null ? form.phone().trim() : null);
        target.setEmail(form.email() != null ? form.email().trim() : null);
        target.setAddress(form.address() != null ? form.address().trim() : null);
        if (form.isActive() != null) {
            target.setIsActive(form.isActive());
        }
    }

    public SupplierSummary toSummary(Supplier s, long receiptCount) {
        if (s == null) {
            return null;
        }
        return new SupplierSummary(
                s.getId(),
                s.getName(),
                s.getContactName(),
                s.getPhone(),
                s.getEmail(),
                s.getAddress(),
                s.getIsActive(),
                s.getCreatedAt(),
                receiptCount
        );
    }
}
