package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.AuthorForm;
import com.ddagtech.aureliabooks.dto.request.BrandForm;
import com.ddagtech.aureliabooks.dto.request.CategoryForm;
import com.ddagtech.aureliabooks.dto.request.PublisherForm;
import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Brand;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Publisher;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.MasterDataMapper;
import com.ddagtech.aureliabooks.repository.AuthorRepository;
import com.ddagtech.aureliabooks.repository.BrandRepository;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.repository.PublisherRepository;
import com.ddagtech.aureliabooks.service.impl.MasterDataServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
 * - UC17: Authors Management (SCR-M03a)
 * - UC18: Publisher Management (SCR-M03b)
 * - UC19: Brand Management (SCR-M03c)
 * - UC20: Hierarchical Category Management (SCR-M03d)
 * Enforces business invariants: duplicate name prevention, cycle detection, and mapper usage.
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

    @Spy
    private MasterDataMapper masterDataMapper = new MasterDataMapper();

    @InjectMocks
    private MasterDataServiceImpl masterDataService;

    // =========================================================================
    // UC17: AUTHORS MANAGEMENT TESTS
    // =========================================================================
    @Nested
    @DisplayName("UC17: Author Management Tests")
    class AuthorTests {

        @Test
        @DisplayName("createAuthor should save and flush entity when name is unique")
        void createAuthor_whenNameUnique_shouldSaveAndFlush() {
            AuthorForm form = new AuthorForm(null, "Nguyễn Nhật Ánh", "Nhà văn Việt Nam nổi tiếng");
            when(authorRepository.existsByNameIgnoreCase("Nguyễn Nhật Ánh")).thenReturn(false);

            Author savedAuthor = Author.builder()
                    .id(1L)
                    .name("Nguyễn Nhật Ánh")
                    .biography("Nhà văn Việt Nam nổi tiếng")
                    .build();
            when(authorRepository.saveAndFlush(any(Author.class))).thenReturn(savedAuthor);

            Long resultId = masterDataService.createAuthor(form);

            assertThat(resultId).isEqualTo(1L);
            verify(authorRepository).saveAndFlush(any(Author.class));
        }

        @Test
        @DisplayName("createAuthor should throw AppException when author name already exists")
        void createAuthor_whenNameExists_shouldThrowException() {
            AuthorForm form = new AuthorForm(null, "Nguyễn Nhật Ánh", "Tiểu sử");
            when(authorRepository.existsByNameIgnoreCase("Nguyễn Nhật Ánh")).thenReturn(true);

            assertThatThrownBy(() -> masterDataService.createAuthor(form))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("đã tồn tại trong hệ thống")
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_INPUT_DATA);

            verify(authorRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("updateAuthor should update entity and flush when name is unique")
        void updateAuthor_whenValid_shouldUpdateAndFlush() {
            Author existing = Author.builder().id(1L).name("Cũ").build();
            AuthorForm form = new AuthorForm(1L, "Mới", "Tiểu sử mới");

            when(authorRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(authorRepository.existsByNameIgnoreCaseAndIdNot("Mới", 1L)).thenReturn(false);

            masterDataService.updateAuthor(1L, form);

            assertThat(existing.getName()).isEqualTo("Mới");
            assertThat(existing.getBiography()).isEqualTo("Tiểu sử mới");
            verify(authorRepository).saveAndFlush(existing);
        }

        @Test
        @DisplayName("updateAuthor should throw AppException when duplicate name exists on other author")
        void updateAuthor_whenDuplicateName_shouldThrowException() {
            Author existing = Author.builder().id(1L).name("Cũ").build();
            AuthorForm form = new AuthorForm(1L, "Trùng Tên", "Tiểu sử");

            when(authorRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(authorRepository.existsByNameIgnoreCaseAndIdNot("Trùng Tên", 1L)).thenReturn(true);

            assertThatThrownBy(() -> masterDataService.updateAuthor(1L, form))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("đã tồn tại trong hệ thống")
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_INPUT_DATA);

            verify(authorRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("toggleAuthorStatus should toggle from active to inactive")
        void toggleAuthorStatus_whenActive_shouldDeactivate() {
            Author author = Author.builder().id(1L).name("Nguyễn Nhật Ánh").isActive(true).build();
            when(authorRepository.findById(1L)).thenReturn(Optional.of(author));

            masterDataService.toggleAuthorStatus(1L);

            assertThat(author.getIsActive()).isFalse();
            verify(authorRepository).save(author);
        }

        @Test
        @DisplayName("toggleAuthorStatus should toggle from inactive to active")
        void toggleAuthorStatus_whenInactive_shouldActivate() {
            Author author = Author.builder().id(1L).name("Nguyễn Nhật Ánh").isActive(false).build();
            when(authorRepository.findById(1L)).thenReturn(Optional.of(author));

            masterDataService.toggleAuthorStatus(1L);

            assertThat(author.getIsActive()).isTrue();
            verify(authorRepository).save(author);
        }

        @Test
        @DisplayName("toggleAuthorStatus should throw AppException when author not found")
        void toggleAuthorStatus_whenNotFound_shouldThrowException() {
            when(authorRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> masterDataService.toggleAuthorStatus(99L))
                    .isInstanceOf(AppException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

            verify(authorRepository, never()).save(any());
        }
    }

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
        @DisplayName("deletePublisher should throw AppException when books are linked to publisher")
        void deletePublisher_HasBooks_ThrowsException() {
            Publisher publisher = Publisher.builder().id(1L).name("NXB Trẻ").build();
            when(publisherRepository.findById(1L)).thenReturn(Optional.of(publisher));
            when(publisherRepository.hasBooks(1L)).thenReturn(true);

            assertThatThrownBy(() -> masterDataService.deletePublisher(1L))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(publisherRepository, never()).delete(any());
        }

        @Test
        @DisplayName("listPublishers should return paged results")
        void listPublishers_ReturnsPagedResult() {
            Pageable pageable = PageRequest.of(0, 10);
            List<Publisher> publishers = List.of(Publisher.builder().id(1L).name("NXB Trẻ").build());
            Page<Publisher> page = new PageImpl<>(publishers, pageable, 1);

            when(publisherRepository.findByNameContainingIgnoreCaseOrderByNameAsc("Trẻ", pageable)).thenReturn(page);

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

            verify(brandRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateBrand should succeed when brand exists and name is valid")
        void updateBrand_Success() {
            Brand existing = Brand.builder().id(10L).name("Pentel").build();
            when(brandRepository.findById(10L)).thenReturn(Optional.of(existing));
            when(brandRepository.existsByNameIgnoreCaseAndIdNot("Pentel Japan", 10L)).thenReturn(false);

            BrandForm form = new BrandForm("Pentel Japan", "Nhật Bản");
            masterDataService.updateBrand(10L, form);

            assertThat(existing.getName()).isEqualTo("Pentel Japan");
            assertThat(existing.getOriginCountry()).isEqualTo("Nhật Bản");
            verify(brandRepository).save(existing);
        }

        @Test
        @DisplayName("deleteBrand should succeed when no stationeries are linked")
        void deleteBrand_Success() {
            Brand brand = Brand.builder().id(10L).name("Deli").build();
            when(brandRepository.findById(10L)).thenReturn(Optional.of(brand));
            when(brandRepository.hasStationeries(10L)).thenReturn(false);

            masterDataService.deleteBrand(10L);

            verify(brandRepository).delete(brand);
        }

        @Test
        @DisplayName("deleteBrand should throw AppException when stationeries are linked to brand")
        void deleteBrand_HasStationeries_ThrowsException() {
            Brand brand = Brand.builder().id(10L).name("Deli").build();
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
        @DisplayName("createCategory should succeed when category name is unique at root")
        void createCategory_RootCategory_Success() {
            CategoryForm form = new CategoryForm("Sách Trong Nước", null, "Danh mục sách", true);
            when(categoryRepository.existsByNameIgnoreCaseAndParentIsNull("Sách Trong Nước")).thenReturn(false);

            Category saved = Category.builder().id(100L).name("Sách Trong Nước").build();
            when(categoryRepository.save(any(Category.class))).thenReturn(saved);

            Long id = masterDataService.createCategory(form);

            assertThat(id).isEqualTo(100L);
            verify(categoryRepository).save(any(Category.class));
        }

        @Test
        @DisplayName("createCategory should succeed when child category has unique name under parent")
        void createCategory_ChildCategory_Success() {
            Category parent = Category.builder().id(100L).name("Sách Trong Nước").build();
            when(categoryRepository.findById(100L)).thenReturn(Optional.of(parent));
            when(categoryRepository.existsByNameIgnoreCaseAndParentId("Văn Học", 100L)).thenReturn(false);

            Category saved = Category.builder().id(101L).name("Văn Học").parent(parent).build();
            when(categoryRepository.save(any(Category.class))).thenReturn(saved);

            CategoryForm form = new CategoryForm("Văn Học", 100L, "Sách văn học", true);
            Long id = masterDataService.createCategory(form);

            assertThat(id).isEqualTo(101L);
            verify(categoryRepository).save(any(Category.class));
        }

        @Test
        @DisplayName("createCategory should throw AppException when duplicate root category exists")
        void createCategory_DuplicateRoot_ThrowsException() {
            CategoryForm form = new CategoryForm("Sách Trong Nước", null, null, true);
            when(categoryRepository.existsByNameIgnoreCaseAndParentIsNull("Sách Trong Nước")).thenReturn(true);

            assertThatThrownBy(() -> masterDataService.createCategory(form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(categoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("createCategory with unchecked active (null or false) should persist with isActive = false")
        void createCategory_UncheckedActive_PersistsAsInactive() {
            CategoryForm form = new CategoryForm("Sách Thanh Lý", null, "Mô tả", false);
            when(categoryRepository.existsByNameIgnoreCaseAndParentIsNull("Sách Thanh Lý")).thenReturn(false);

            Category saved = Category.builder().id(105L).name("Sách Thanh Lý").isActive(false).build();
            when(categoryRepository.save(any(Category.class))).thenReturn(saved);

            Long id = masterDataService.createCategory(form);

            assertThat(id).isEqualTo(105L);
            ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
            verify(categoryRepository).save(captor.capture());
            assertThat(captor.getValue().getIsActive()).isFalse();
        }

        @Test
        @DisplayName("updateCategory should prevent self-parenting")
        void updateCategory_SelfParent_ThrowsException() {
            CategoryForm form = new CategoryForm("Sách Trong Nước", 100L, null, true);

            assertThatThrownBy(() -> masterDataService.updateCategory(100L, form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(categoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateCategory with unchecked active (null or false) should update isActive to false")
        void updateCategory_UncheckedActive_UpdatesToInactive() {
            Category existing = Category.builder().id(100L).name("Sách Trong Nước").isActive(true).build();
            when(categoryRepository.findById(100L)).thenReturn(Optional.of(existing));

            CategoryForm form = new CategoryForm("Sách Trong Nước", null, "Mô tả", false);
            masterDataService.updateCategory(100L, form);

            assertThat(existing.getIsActive()).isFalse();
            verify(categoryRepository).save(existing);
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
        @DisplayName("updateCategory should abort and throw exception when circular cycle graph exists")
        void updateCategory_CircularGraph_TerminatesAndThrowsException() {
            // Setup circular loop: A -> B -> A
            Category catA = Category.builder().id(1L).name("A").build();
            Category catB = Category.builder().id(2L).name("B").parent(catA).build();
            catA.setParent(catB); // Circular reference

            when(categoryRepository.findById(1L)).thenReturn(Optional.of(catA));
            when(categoryRepository.findById(2L)).thenReturn(Optional.of(catB));

            CategoryForm form = new CategoryForm("A", 2L, "Mô tả", true);

            assertThatThrownBy(() -> masterDataService.updateCategory(1L, form))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("phát hiện chu trình vòng lặp");
        }

        @Test
        @DisplayName("createCategory should convert pessimistic locking failure to AppException")
        void createCategory_PessimisticLockFailure_ThrowsAppException() {
            CategoryForm form = new CategoryForm("Văn Học", 100L, "Mô tả", true);
            when(categoryRepository.findByIdWithLock(100L))
                    .thenThrow(new org.springframework.dao.PessimisticLockingFailureException("Lock conflict"));

            assertThatThrownBy(() -> masterDataService.createCategory(form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA)
                    .hasMessageContaining("xung đột");
        }

        @Test
        @DisplayName("updateCategory should throw AppException when sibling name duplicates under same parent")
        void updateCategory_whenDuplicateSibling_shouldThrowException() {
            Category child = Category.builder().id(2L).name("Truyện Tranh").build();
            Category parent = Category.builder().id(1L).name("Sách Thiếu Nhi").build();
            CategoryForm form = new CategoryForm("Văn Học Thiếu Nhi", 1L, "Mô tả", true);

            when(categoryRepository.findById(2L)).thenReturn(Optional.of(child));
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(parent));
            when(categoryRepository.existsByNameIgnoreCaseAndParentIdAndIdNot("Văn Học Thiếu Nhi", 1L, 2L)).thenReturn(true);

            assertThatThrownBy(() -> masterDataService.updateCategory(2L, form))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("đã tồn tại dưới danh mục cha đã chọn")
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_INPUT_DATA);

            verify(categoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateCategory should throw AppException when root category name duplicates")
        void updateCategory_whenDuplicateRoot_shouldThrowException() {
            Category root = Category.builder().id(2L).name("Sách Cũ").build();
            CategoryForm form = new CategoryForm("Sách Mới", null, "Mô tả", true);

            when(categoryRepository.findById(2L)).thenReturn(Optional.of(root));
            when(categoryRepository.existsByNameIgnoreCaseAndParentIsNullAndIdNot("Sách Mới", 2L)).thenReturn(true);

            assertThatThrownBy(() -> masterDataService.updateCategory(2L, form))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("Danh mục gốc 'Sách Mới' đã tồn tại")
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_INPUT_DATA);

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
