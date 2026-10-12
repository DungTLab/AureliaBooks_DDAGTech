package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.SupplierForm;
import com.ddagtech.aureliabooks.dto.response.SupplierSummary;
import com.ddagtech.aureliabooks.entity.Supplier;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.GoodsReceiptRepository;
import com.ddagtech.aureliabooks.repository.SupplierRepository;
import com.ddagtech.aureliabooks.service.impl.SupplierServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

    @Mock
    private SupplierRepository supplierRepository;

    @Mock
    private GoodsReceiptRepository goodsReceiptRepository;

    @InjectMocks
    private SupplierServiceImpl supplierService;

    private Supplier sampleSupplier;

    @BeforeEach
    void setUp() {
        sampleSupplier = Supplier.builder()
                .id(1L)
                .name("Nhà Xuất Bản Trẻ")
                .contactName("Nguyễn Văn A")
                .phone("0912345678")
                .email("nxbtre@example.com")
                .address("161B Lý Chính Thắng, Q.3, TP.HCM")
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("Create Supplier Tests")
    class CreateSupplierTests {

        @Test
        @DisplayName("Should successfully create supplier when inputs are valid")
        void createSupplier_Success() {
            SupplierForm form = new SupplierForm(
                    "Nhà Xuất Bản Kim Đồng",
                    "Trần Thị B",
                    "0987654321",
                    "kimdong@example.com",
                    "55 Quang Trung, Hà Nội",
                    true
            );

            when(supplierRepository.existsByPhone("0987654321")).thenReturn(false);
            when(supplierRepository.save(any(Supplier.class))).thenAnswer(invocation -> {
                Supplier s = invocation.getArgument(0);
                s.setId(2L);
                return s;
            });

            Long createdId = supplierService.create(form);

            assertThat(createdId).isEqualTo(2L);
            verify(supplierRepository).save(any(Supplier.class));
        }

        @Test
        @DisplayName("Should allow creating supplier with same name if phone number is different")
        void createSupplier_SameNameDifferentPhone_Success() {
            SupplierForm form = new SupplierForm(
                    "Nhà Xuất Bản Trẻ",
                    "Chi nhánh Hà Nội",
                    "0911223344",
                    "hanoi@example.com",
                    "Địa chỉ Hà Nội",
                    true
            );

            when(supplierRepository.existsByPhone("0911223344")).thenReturn(false);
            when(supplierRepository.save(any(Supplier.class))).thenAnswer(invocation -> {
                Supplier s = invocation.getArgument(0);
                s.setId(3L);
                return s;
            });

            Long createdId = supplierService.create(form);

            assertThat(createdId).isEqualTo(3L);
            verify(supplierRepository).save(any(Supplier.class));
        }

        @Test
        @DisplayName("Should throw AppException when supplier phone already exists")
        void createSupplier_DuplicatePhone_ThrowsException() {
            SupplierForm form = new SupplierForm(
                    "Công Ty Mới",
                    "Người liên hệ",
                    "0912345678",
                    "test@example.com",
                    "Địa chỉ",
                    true
            );

            when(supplierRepository.existsByPhone("0912345678")).thenReturn(true);

            assertThatThrownBy(() -> supplierService.create(form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA)
                    .hasMessageContaining("Số điện thoại");

            verify(supplierRepository, never()).save(any(Supplier.class));
        }
    }

    @Nested
    @DisplayName("Update Supplier Tests")
    class UpdateSupplierTests {

        @Test
        @DisplayName("Should update supplier successfully with valid inputs")
        void updateSupplier_Success() {
            SupplierForm form = new SupplierForm(
                    "Nhà Xuất Bản Trẻ (Cập nhật)",
                    "Nguyễn Văn C",
                    "0912345678",
                    "updated@example.com",
                    "Địa chỉ mới",
                    true
            );

            when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));
            when(supplierRepository.existsByPhoneAndIdNot("0912345678", 1L)).thenReturn(false);

            supplierService.update(1L, form);

            assertThat(sampleSupplier.getName()).isEqualTo("Nhà Xuất Bản Trẻ (Cập nhật)");
            assertThat(sampleSupplier.getContactName()).isEqualTo("Nguyễn Văn C");
            assertThat(sampleSupplier.getEmail()).isEqualTo("updated@example.com");
            assertThat(sampleSupplier.getAddress()).isEqualTo("Địa chỉ mới");
            verify(supplierRepository).save(sampleSupplier);
        }

        @Test
        @DisplayName("Should throw AppException when supplier ID does not exist")
        void updateSupplier_NotFound_ThrowsException() {
            SupplierForm form = new SupplierForm(
                    "NCC Bất Kỳ", null, "0912345678", null, null, true
            );

            when(supplierRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> supplierService.update(999L, form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

            verify(supplierRepository, never()).save(any(Supplier.class));
        }

        @Test
        @DisplayName("Should throw AppException when update phone collides with another supplier")
        void updateSupplier_DuplicatePhone_ThrowsException() {
            SupplierForm form = new SupplierForm(
                    "Nhà Xuất Bản Kim Đồng", null, "0987654321", null, null, true
            );

            when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));
            when(supplierRepository.existsByPhoneAndIdNot("0987654321", 1L)).thenReturn(true);

            assertThatThrownBy(() -> supplierService.update(1L, form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA)
                    .hasMessageContaining("Số điện thoại");

            verify(supplierRepository, never()).save(sampleSupplier);
        }
    }

    @Nested
    @DisplayName("Status Toggle Tests (Active/Inactive)")
    class StatusToggleTests {

        @Test
        @DisplayName("Should deactivate supplier")
        void deactivateSupplier_Success() {
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));

            supplierService.deactivate(1L);

            assertThat(sampleSupplier.getIsActive()).isFalse();
            verify(supplierRepository).save(sampleSupplier);
        }

        @Test
        @DisplayName("Should activate supplier")
        void activateSupplier_Success() {
            sampleSupplier.setIsActive(false);
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));

            supplierService.activate(1L);

            assertThat(sampleSupplier.getIsActive()).isTrue();
            verify(supplierRepository).save(sampleSupplier);
        }

        @Test
        @DisplayName("Should toggle active status")
        void toggleActive_Success() {
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));

            supplierService.toggleActive(1L, false);
            assertThat(sampleSupplier.getIsActive()).isFalse();

            supplierService.toggleActive(1L, true);
            assertThat(sampleSupplier.getIsActive()).isTrue();

            verify(supplierRepository, times(2)).save(sampleSupplier);
        }
    }

    @Nested
    @DisplayName("Delete Supplier & Receipt Protection Tests (UC30 Core Rule)")
    class DeleteSupplierTests {

        @Test
        @DisplayName("CẤM XÓA: Should prohibit hard deletion when supplier has receipts and throw AppException")
        void deleteSupplier_HasReceipts_ThrowsException_BlocksDeletion() {
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));
            when(goodsReceiptRepository.existsBySupplierId(1L)).thenReturn(true);

            assertThatThrownBy(() -> supplierService.delete(1L))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA)
                    .hasMessageContaining("Không thể xóa nhà cung cấp đã phát sinh đơn nhập hàng");

            // Verify hard delete was NEVER called
            verify(supplierRepository, never()).delete(any(Supplier.class));
        }

        @Test
        @DisplayName("Should successfully hard delete supplier when NO receipts exist")
        void deleteSupplier_NoReceipts_Success() {
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));
            when(goodsReceiptRepository.existsBySupplierId(1L)).thenReturn(false);

            supplierService.delete(1L);

            verify(supplierRepository).delete(sampleSupplier);
        }

        @Test
        @DisplayName("Should throw RESOURCE_NOT_FOUND when deleting non-existent supplier")
        void deleteSupplier_NotFound_ThrowsException() {
            when(supplierRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> supplierService.delete(999L))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

            verify(goodsReceiptRepository, never()).existsBySupplierId(anyLong());
            verify(supplierRepository, never()).delete(any(Supplier.class));
        }
    }

    @Nested
    @DisplayName("Listing and Query Tests")
    class ListingTests {

        @Test
        @DisplayName("Should return paginated list of suppliers with receipt counts")
        void listSuppliers_Success() {
            Pageable pageable = PageRequest.of(0, 10);
            List<Supplier> content = List.of(sampleSupplier);
            Page<Supplier> page = new PageImpl<>(content, pageable, 1);

            when(supplierRepository.searchSuppliers("Trẻ", true, pageable)).thenReturn(page);
            when(goodsReceiptRepository.countBySupplierId(1L)).thenReturn(3L);

            Page<SupplierSummary> result = supplierService.list("Trẻ", true, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            SupplierSummary summary = result.getContent().get(0);
            assertThat(summary.name()).isEqualTo("Nhà Xuất Bản Trẻ");
            assertThat(summary.receiptCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("Should retrieve active suppliers sorted by name")
        void getActiveSuppliers_Success() {
            when(supplierRepository.findByIsActiveTrueOrderByNameAsc()).thenReturn(List.of(sampleSupplier));
            when(goodsReceiptRepository.countBySupplierId(1L)).thenReturn(1L);

            List<SupplierSummary> activeSuppliers = supplierService.getActiveSuppliers();

            assertThat(activeSuppliers).hasSize(1);
            assertThat(activeSuppliers.get(0).id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should return summary by ID")
        void getSummaryById_Success() {
            when(supplierRepository.findById(1L)).thenReturn(Optional.of(sampleSupplier));
            when(goodsReceiptRepository.countBySupplierId(1L)).thenReturn(5L);

            SupplierSummary summary = supplierService.getSummaryById(1L);

            assertThat(summary.id()).isEqualTo(1L);
            assertThat(summary.receiptCount()).isEqualTo(5L);
        }
    }
}
