package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.SupplierForm;
import com.ddagtech.aureliabooks.dto.response.SupplierSummary;
import com.ddagtech.aureliabooks.entity.Supplier;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.GoodsReceiptRepository;
import com.ddagtech.aureliabooks.repository.SupplierRepository;
import com.ddagtech.aureliabooks.service.SupplierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Default implementation of {@link SupplierService} (UC30).
 * Enforces business constraints including receipt integrity checks on deletion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplierServiceImpl implements SupplierService {

    private final SupplierRepository supplierRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;

    @Override
    @Transactional
    public Long create(SupplierForm form) {
        if (form == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Dữ liệu nhà cung cấp không được để trống.");
        }

        if (supplierRepository.existsByPhone(form.phone())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Số điện thoại đã được đăng ký cho nhà cung cấp khác.");
        }

        Supplier supplier = Supplier.builder()
                .name(form.name())
                .contactName(form.contactName())
                .phone(form.phone())
                .email(form.email())
                .address(form.address())
                .isActive(form.isActive() != null ? form.isActive() : true)
                .build();

        Supplier saved = supplierRepository.save(supplier);
        log.info("Created new supplier with ID: {}, Name: '{}'", saved.getId(), saved.getName());
        return saved.getId();
    }

    @Override
    @Transactional
    public void update(Long supplierId, SupplierForm form) {
        if (form == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Dữ liệu cập nhật không được để trống.");
        }

        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy nhà cung cấp với ID: " + supplierId));

        if (supplierRepository.existsByPhoneAndIdNot(form.phone(), supplierId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Số điện thoại đã được đăng ký cho nhà cung cấp khác.");
        }

        supplier.setName(form.name());
        supplier.setContactName(form.contactName());
        supplier.setPhone(form.phone());
        supplier.setEmail(form.email());
        supplier.setAddress(form.address());
        if (form.isActive() != null) {
            supplier.setIsActive(form.isActive());
        }

        supplierRepository.save(supplier);
        log.info("Updated supplier ID: {}, Name: '{}'", supplierId, supplier.getName());
    }

    @Override
    @Transactional
    public void deactivate(Long supplierId) {
        toggleActive(supplierId, false);
    }

    @Override
    @Transactional
    public void activate(Long supplierId) {
        toggleActive(supplierId, true);
    }

    @Override
    @Transactional
    public void toggleActive(Long supplierId, boolean active) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy nhà cung cấp với ID: " + supplierId));

        supplier.setIsActive(active);
        supplierRepository.save(supplier);
        log.info("Changed supplier ID: {} active status to: {}", supplierId, active);
    }

    @Override
    @Transactional
    public void delete(Long supplierId) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy nhà cung cấp với ID: " + supplierId));

        // Core Business Rule (UC30): Chặn hard-delete NCC có receipt
        if (goodsReceiptRepository.existsBySupplierId(supplierId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Không thể xóa nhà cung cấp đã có chứng từ nhập hàng trong hệ thống. Vui lòng chuyển trạng thái sang Ngừng hợp tác (Inactive).");
        }

        supplierRepository.delete(supplier);
        log.info("Permanently deleted supplier ID: {}, Name: '{}'", supplierId, supplier.getName());
    }

    @Override
    public Supplier getById(Long supplierId) {
        return supplierRepository.findById(supplierId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy nhà cung cấp với ID: " + supplierId));
    }

    @Override
    public SupplierSummary getSummaryById(Long supplierId) {
        Supplier supplier = getById(supplierId);
        long receiptCount = goodsReceiptRepository.countBySupplierId(supplierId);
        return mapToSummary(supplier, receiptCount);
    }

    @Override
    public Page<SupplierSummary> list(String keyword, Boolean active, Pageable pageable) {
        String cleanKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        Page<Supplier> page = supplierRepository.searchSuppliers(cleanKeyword, active, pageable);
        return page.map(s -> {
            long receiptCount = goodsReceiptRepository.countBySupplierId(s.getId());
            return mapToSummary(s, receiptCount);
        });
    }

    @Override
    public List<SupplierSummary> getActiveSuppliers() {
        return supplierRepository.findByIsActiveTrueOrderByNameAsc().stream()
                .map(s -> mapToSummary(s, goodsReceiptRepository.countBySupplierId(s.getId())))
                .toList();
    }

    @Override
    public boolean hasReceipts(Long supplierId) {
        return goodsReceiptRepository.existsBySupplierId(supplierId);
    }

    private SupplierSummary mapToSummary(Supplier s, long receiptCount) {
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
