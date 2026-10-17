package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.BrandForm;
import com.ddagtech.aureliabooks.dto.request.CategoryForm;
import com.ddagtech.aureliabooks.dto.request.PublisherForm;
import com.ddagtech.aureliabooks.entity.Brand;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Publisher;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.AuthorRepository;
import com.ddagtech.aureliabooks.repository.BrandRepository;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.repository.PublisherRepository;
import com.ddagtech.aureliabooks.service.impl.MasterDataServiceImpl;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test suite for MasterDataService covering:
 * - UC18: Publisher Management (SCR-M03b)
 * - UC19: Brand Management (SCR-M03c)
 * - UC20: Hierarchical Category Management (SCR-M03d)
 */
@ExtendWith(MockitoExtension.class)
class MasterDataServiceTest {

    @Mock
    private AuthorRepository authorRepository;

    @Mock
    private PublisherRepository publisherRepository;

    @Mock
    private BrandRepository brandRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private MasterDataServiceImpl masterDataService;

    // =========================================================================
    // UC18: PUBLISHERS MANAGEMENT TESTS
    // =========================================================================
    @Nested
    @DisplayName("UC18: Publisher Management Tests")
    class PublisherTests {

        @Test
        @DisplayName("createPublisher should succeed when publisher name is unique")
        void createPublisher_Success() {
            PublisherForm form = new PublisherForm("NXB Trẻ", "161B Lý Chính Thắng, Q3, TP.HCM", "hopthu@nxbtre.com.vn");
            when(publisherRepository.existsByNameIgnoreCase("NXB Trẻ")).thenReturn(false);

            Publisher savedPublisher = Publisher.builder()
                    .id(1L)
                    .name("NXB Trẻ")
                    .address(form.address())
                    .email(form.email())
                    .build();
            when(publisherRepository.save(any(Publisher.class))).thenReturn(savedPublisher);

            Long id = masterDataService.createPublisher(form);

            assertThat(id).isEqualTo(1L);
            verify(publisherRepository).save(any(Publisher.class));
        }

        @Test
        @DisplayName("createPublisher should throw AppException when publisher name already exists")
        void createPublisher_DuplicateName_ThrowsException() {
            PublisherForm form = new PublisherForm("NXB Kim Đồng", "Hà Nội", "info@nxbkimdong.com.vn");
            when(publisherRepository.existsByNameIgnoreCase("NXB Kim Đồng")).thenReturn(true);

            assertThatThrownBy(() -> masterDataService.createPublisher(form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(publisherRepository, never()).save(any(Publisher.class));
        }

        @Test
        @DisplayName("updatePublisher should succeed when publisher exists and name is valid")
        void updatePublisher_Success() {
            Publisher existing = Publisher.builder().id(1L).name("NXB Trẻ").build();
            when(publisherRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(publisherRepository.existsByNameIgnoreCaseAndIdNot("NXB Trẻ Mới", 1L)).thenReturn(false);

            PublisherForm form = new PublisherForm("NXB Trẻ Mới", "Địa chỉ mới", "email@moi.vn");
            masterDataService.updatePublisher(1L, form);

            assertThat(existing.getName()).isEqualTo("NXB Trẻ Mới");
            assertThat(existing.getAddress()).isEqualTo("Địa chỉ mới");
            assertThat(existing.getEmail()).isEqualTo("email@moi.vn");
            verify(publisherRepository).save(existing);
        }

        @Test
        @DisplayName("updatePublisher should throw AppException when publisher not found")
        void updatePublisher_NotFound_ThrowsException() {
            when(publisherRepository.findById(99L)).thenReturn(Optional.empty());
            PublisherForm form = new PublisherForm("NXB Lạ", null, null);

            assertThatThrownBy(() -> masterDataService.updatePublisher(99L, form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("deletePublisher should succeed when no books are linked")
        void deletePublisher_Success() {
            Publisher publisher = Publisher.builder().id(1L).name("NXB Trẻ").build();
            when(publisherRepository.findById(1L)).thenReturn(Optional.of(publisher));
            when(publisherRepository.hasBooks(1L)).thenReturn(false);

            masterDataService.deletePublisher(1L);

            verify(publisherRepository).delete(publisher);
        }

        @Test
        @DisplayName("deletePublisher should throw AppException when books are linked")
        void deletePublisher_LinkedBooks_ThrowsException() {
            Publisher publisher = Publisher.builder().id(1L).name("NXB Trẻ").build();
            when(publisherRepository.findById(1L)).thenReturn(Optional.of(publisher));
            when(publisherRepository.hasBooks(1L)).thenReturn(true);

            assertThatThrownBy(() -> masterDataService.deletePublisher(1L))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(publisherRepository, never()).delete(any());
        }

        @Test
        @DisplayName("listPublishers should filter by keyword when keyword is present")
        void listPublishers_WithKeyword() {
            Pageable pageable = PageRequest.of(0, 10);
            List<Publisher> content = List.of(Publisher.builder().id(1L).name("NXB Trẻ").build());
            Page<Publisher> expectedPage = new PageImpl<>(content, pageable, 1);

            when(publisherRepository.findByNameContainingIgnoreCaseOrderByNameAsc("Trẻ", pageable)).thenReturn(expectedPage);

            Page<Publisher> result = masterDataService.listPublishers("Trẻ", pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getName()).isEqualTo("NXB Trẻ");
        }
    }

    // =========================================================================
    // UC19: BRANDS MANAGEMENT TESTS
    // =========================================================================
    @Nested
    @DisplayName("UC19: Brand Management Tests")
    class BrandTests {

        @Test
        @DisplayName("createBrand should succeed when brand name is unique")
        void createBrand_Success() {
            BrandForm form = new BrandForm("Thiên Long", "Việt Nam");
            when(brandRepository.existsByNameIgnoreCase("Thiên Long")).thenReturn(false);

            Brand savedBrand = Brand.builder().id(10L).name("Thiên Long").originCountry("Việt Nam").build();
            when(brandRepository.save(any(Brand.class))).thenReturn(savedBrand);

            Long id = masterDataService.createBrand(form);

            assertThat(id).isEqualTo(10L);
            verify(brandRepository).save(any(Brand.class));
        }

        @Test
        @DisplayName("createBrand should throw AppException when brand name already exists")
        void createBrand_DuplicateName_ThrowsException() {
            BrandForm form = new BrandForm("Thiên Long", "Việt Nam");
            when(brandRepository.existsByNameIgnoreCase("Thiên Long")).thenReturn(true);

            assertThatThrownBy(() -> masterDataService.createBrand(form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(brandRepository, never()).save(any(Brand.class));
        }

        @Test
        @DisplayName("updateBrand should succeed when valid")
        void updateBrand_Success() {
            Brand existing = Brand.builder().id(10L).name("Pentel").originCountry("Nhật Bản").build();
            when(brandRepository.findById(10L)).thenReturn(Optional.of(existing));
            when(brandRepository.existsByNameIgnoreCaseAndIdNot("Pentel Japan", 10L)).thenReturn(false);

            BrandForm form = new BrandForm("Pentel Japan", "Japan");
            masterDataService.updateBrand(10L, form);

            assertThat(existing.getName()).isEqualTo("Pentel Japan");
            assertThat(existing.getOriginCountry()).isEqualTo("Japan");
            verify(brandRepository).save(existing);
        }

        @Test
        @DisplayName("deleteBrand should succeed when no stationeries linked")
        void deleteBrand_Success() {
            Brand brand = Brand.builder().id(10L).name("Deli").build();
            when(brandRepository.findById(10L)).thenReturn(Optional.of(brand));
            when(brandRepository.hasStationeries(10L)).thenReturn(false);

            masterDataService.deleteBrand(10L);

            verify(brandRepository).delete(brand);
        }

        @Test
        @DisplayName("deleteBrand should throw AppException when stationeries linked")
        void deleteBrand_LinkedStationeries_ThrowsException() {
            Brand brand = Brand.builder().id(10L).name("Thiên Long").build();
            when(brandRepository.findById(10L)).thenReturn(Optional.of(brand));
            when(brandRepository.hasStationeries(10L)).thenReturn(true);

            assertThatThrownBy(() -> masterDataService.deleteBrand(10L))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(brandRepository, never()).delete(any());
        }
    }

    // =========================================================================
    // UC20: CATEGORIES MANAGEMENT TESTS
    // =========================================================================
    @Nested
    @DisplayName("UC20: Hierarchical Category Management Tests")
    class CategoryTests {

        @Test
        @DisplayName("createCategory should succeed for root category when name is unique")
        void createCategory_RootCategory_Success() {
            CategoryForm form = new CategoryForm("Sách Trong Nước", null, "Sách quốc văn", true);
            when(categoryRepository.existsByNameIgnoreCaseAndParentIsNull("Sách Trong Nước")).thenReturn(false);

            Category saved = Category.builder()
                    .id(100L)
                    .name("Sách Trong Nước")
                    .parent(null)
                    .description("Sách quốc văn")
                    .isActive(true)
                    .build();
            when(categoryRepository.save(any(Category.class))).thenReturn(saved);

            Long id = masterDataService.createCategory(form);

            assertThat(id).isEqualTo(100L);
            verify(categoryRepository).save(any(Category.class));
        }

        @Test
        @DisplayName("createCategory should succeed for child category when parent exists and name is unique in parent")
        void createCategory_ChildCategory_Success() {
            Category parent = Category.builder().id(100L).name("Sách Trong Nước").build();
            when(categoryRepository.findById(100L)).thenReturn(Optional.of(parent));
            when(categoryRepository.existsByNameIgnoreCaseAndParentId("Văn Học", 100L)).thenReturn(false);

            CategoryForm form = new CategoryForm("Văn Học", 100L, "Tiểu thuyết, thơ", true);
            Category savedChild = Category.builder()
                    .id(101L)
                    .name("Văn Học")
                    .parent(parent)
                    .isActive(true)
                    .build();
            when(categoryRepository.save(any(Category.class))).thenReturn(savedChild);

            Long id = masterDataService.createCategory(form);

            assertThat(id).isEqualTo(101L);
            verify(categoryRepository).save(any(Category.class));
        }

        @Test
        @DisplayName("createCategory should throw AppException when duplicate name in same parent group")
        void createCategory_DuplicateNameInParent_ThrowsException() {
            Category parent = Category.builder().id(100L).name("Sách Trong Nước").build();
            when(categoryRepository.findById(100L)).thenReturn(Optional.of(parent));
            when(categoryRepository.existsByNameIgnoreCaseAndParentId("Văn Học", 100L)).thenReturn(true);

            CategoryForm form = new CategoryForm("Văn Học", 100L, null, true);

            assertThatThrownBy(() -> masterDataService.createCategory(form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(categoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateCategory should prevent self-parenting")
        void updateCategory_SelfParent_ThrowsException() {
            Category existing = Category.builder().id(100L).name("Sách Trong Nước").build();
            when(categoryRepository.findById(100L)).thenReturn(Optional.of(existing));

            CategoryForm form = new CategoryForm("Sách Trong Nước", 100L, null, true);

            assertThatThrownBy(() -> masterDataService.updateCategory(100L, form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(categoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateCategory should prevent circular reference (cycle detection)")
        void updateCategory_CycleDetection_ThrowsException() {
            // Hierarchy: Root(100) -> Child(101) -> GrandChild(102)
            Category root = Category.builder().id(100L).name("Root").build();
            Category child = Category.builder().id(101L).name("Child").parent(root).build();
            Category grandChild = Category.builder().id(102L).name("GrandChild").parent(child).build();

            when(categoryRepository.findById(100L)).thenReturn(Optional.of(root));
            when(categoryRepository.findById(102L)).thenReturn(Optional.of(grandChild));

            // Attempt to set Root's parent to GrandChild (102)
            CategoryForm form = new CategoryForm("Root", 102L, null, true);

            assertThatThrownBy(() -> masterDataService.updateCategory(100L, form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(categoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("deleteCategory should throw AppException when category has child categories")
        void deleteCategory_HasChildren_ThrowsException() {
            Category category = Category.builder().id(100L).name("Sách Trong Nước").build();
            when(categoryRepository.findById(100L)).thenReturn(Optional.of(category));
            when(categoryRepository.existsByParentId(100L)).thenReturn(true);

            assertThatThrownBy(() -> masterDataService.deleteCategory(100L))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(categoryRepository, never()).delete(any());
        }

        @Test
        @DisplayName("deleteCategory should throw AppException when category has linked products")
        void deleteCategory_HasProducts_ThrowsException() {
            Category category = Category.builder().id(101L).name("Văn Học").build();
            when(categoryRepository.findById(101L)).thenReturn(Optional.of(category));
            when(categoryRepository.existsByParentId(101L)).thenReturn(false);
            when(categoryRepository.hasProducts(101L)).thenReturn(true);

            assertThatThrownBy(() -> masterDataService.deleteCategory(101L))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(categoryRepository, never()).delete(any());
        }

        @Test
        @DisplayName("toggleCategoryStatus should invert isActive boolean flag")
        void toggleCategoryStatus_InvertsActiveFlag() {
            Category category = Category.builder().id(100L).name("Văn Học").isActive(true).build();
            when(categoryRepository.findById(100L)).thenReturn(Optional.of(category));

            masterDataService.toggleCategoryStatus(100L);

            assertThat(category.getIsActive()).isFalse();
            verify(categoryRepository).save(category);
        }
    }
}
