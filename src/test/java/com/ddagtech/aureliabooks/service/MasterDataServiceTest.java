package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.AuthorForm;
import com.ddagtech.aureliabooks.dto.request.CategoryForm;
import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.MasterDataMapper;
import com.ddagtech.aureliabooks.repository.AuthorRepository;
import com.ddagtech.aureliabooks.repository.BrandRepository;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.repository.PublisherRepository;
import com.ddagtech.aureliabooks.service.impl.MasterDataServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test suite for MasterDataService (UC17: Authors, UC20: Categories).
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

    @Nested
    @DisplayName("Author Management Tests (UC17)")
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
    }

    @Nested
    @DisplayName("Category Management Tests (UC20)")
    class CategoryTests {

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
    }
}
