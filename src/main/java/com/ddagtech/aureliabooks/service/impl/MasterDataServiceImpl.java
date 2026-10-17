package com.ddagtech.aureliabooks.service.impl;

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
import com.ddagtech.aureliabooks.repository.AuthorRepository;
import com.ddagtech.aureliabooks.repository.BrandRepository;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.repository.PublisherRepository;
import com.ddagtech.aureliabooks.service.MasterDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service implementation for managing master taxonomies (UC17: Authors, UC18: Publishers, UC19: Brands, UC20: Categories).
 * Enforces business validation, name uniqueness, relational reference preservation, and tree cycle avoidance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MasterDataServiceImpl implements MasterDataService {

    private final AuthorRepository authorRepository;
    private final PublisherRepository publisherRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;

    // =========================================================================
    // UC17: AUTHORS MANAGEMENT (SCR-M03a)
    // =========================================================================

    @Override
    @Transactional
    public Long createAuthor(AuthorForm form) {
        String trimmedName = form.name() != null ? form.name().trim() : "";
        if (authorRepository.existsByNameIgnoreCase(trimmedName)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tác giả với tên '" + trimmedName + "' đã tồn tại trong hệ thống.");
        }

        Author author = Author.builder()
                .name(trimmedName)
                .biography(form.biography() != null ? form.biography().trim() : null)
                .build();

        Author saved = authorRepository.save(author);
        log.info("Created new author [id={}, name='{}']", saved.getId(), saved.getName());
        return saved.getId();
    }

    @Override
    @Transactional
    public void updateAuthor(Long authorId, AuthorForm form) {
        Author author = authorRepository.findById(authorId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy tác giả với ID: " + authorId));

        String trimmedName = form.name() != null ? form.name().trim() : "";
        if (authorRepository.existsByNameIgnoreCaseAndIdNot(trimmedName, authorId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tác giả với tên '" + trimmedName + "' đã tồn tại trong hệ thống.");
        }

        author.setName(trimmedName);
        author.setBiography(form.biography() != null ? form.biography().trim() : null);
        authorRepository.save(author);
        log.info("Updated author [id={}, name='{}']", authorId, trimmedName);
    }

    @Override
    @Transactional
    public void deleteAuthor(Long authorId) {
        Author author = authorRepository.findById(authorId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy tác giả với ID: " + authorId));

        if (authorRepository.hasBooks(authorId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Không thể xóa tác giả '" + author.getName() + "' vì đang có sách trong hệ thống liên kết với tác giả này.");
        }

        authorRepository.delete(author);
        log.info("Deleted author [id={}, name='{}']", authorId, author.getName());
    }

    @Override
    public Page<Author> listAuthors(String keyword, Pageable pageable) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            return authorRepository.findByNameContainingIgnoreCaseOrderByNameAsc(keyword.trim(), pageable);
        }
        return authorRepository.findAllByOrderByNameAsc(pageable);
    }

    @Override
    public Author getAuthorById(Long authorId) {
        return authorRepository.findById(authorId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy tác giả với ID: " + authorId));
    }

    @Override
    public long countBooksByAuthor(Long authorId) {
        return authorRepository.countBooksByAuthorId(authorId);
    }

    @Override
    public List<Author> getAllAuthors() {
        return authorRepository.findAllByOrderByNameAsc();
    }

    // =========================================================================
    // UC18: PUBLISHERS MANAGEMENT (SCR-M03b)
    // =========================================================================

    @Override
    @Transactional
    public Long createPublisher(PublisherForm form) {
        String trimmedName = form.name() != null ? form.name().trim() : "";
        if (publisherRepository.existsByNameIgnoreCase(trimmedName)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Nhà xuất bản '" + trimmedName + "' đã tồn tại.");
        }

        Publisher publisher = Publisher.builder()
                .name(trimmedName)
                .address(form.address() != null ? form.address().trim() : null)
                .email(form.email() != null ? form.email().trim() : null)
                .build();

        Publisher saved = publisherRepository.save(publisher);
        log.info("Created publisher [id={}, name='{}']", saved.getId(), saved.getName());
        return saved.getId();
    }

    @Override
    @Transactional
    public void updatePublisher(Long publisherId, PublisherForm form) {
        Publisher publisher = publisherRepository.findById(publisherId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy nhà xuất bản với ID: " + publisherId));

        String trimmedName = form.name() != null ? form.name().trim() : "";
        if (publisherRepository.existsByNameIgnoreCaseAndIdNot(trimmedName, publisherId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Nhà xuất bản '" + trimmedName + "' đã tồn tại.");
        }

        publisher.setName(trimmedName);
        publisher.setAddress(form.address() != null ? form.address().trim() : null);
        publisher.setEmail(form.email() != null ? form.email().trim() : null);
        publisherRepository.save(publisher);
        log.info("Updated publisher [id={}, name='{}']", publisherId, trimmedName);
    }

    @Override
    @Transactional
    public void deletePublisher(Long publisherId) {
        Publisher publisher = publisherRepository.findById(publisherId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy nhà xuất bản với ID: " + publisherId));

        if (publisherRepository.hasBooks(publisherId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Không thể xóa nhà xuất bản '" + publisher.getName() + "' vì đang có đầu sách liên kết.");
        }

        publisherRepository.delete(publisher);
        log.info("Deleted publisher [id={}, name='{}']", publisherId, publisher.getName());
    }

    @Override
    public Page<Publisher> listPublishers(String keyword, Pageable pageable) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            return publisherRepository.findByNameContainingIgnoreCaseOrderByNameAsc(keyword.trim(), pageable);
        }
        return publisherRepository.findAllByOrderByNameAsc(pageable);
    }

    @Override
    public Publisher getPublisherById(Long publisherId) {
        return publisherRepository.findById(publisherId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy nhà xuất bản với ID: " + publisherId));
    }

    @Override
    public long countBooksByPublisher(Long publisherId) {
        return publisherRepository.countBooksByPublisherId(publisherId);
    }

    @Override
    public List<Publisher> getAllPublishers() {
        return publisherRepository.findAllByOrderByNameAsc();
    }

    // =========================================================================
    // UC19: BRANDS MANAGEMENT (SCR-M03c)
    // =========================================================================

    @Override
    @Transactional
    public Long createBrand(BrandForm form) {
        String trimmedName = form.name() != null ? form.name().trim() : "";
        if (brandRepository.existsByNameIgnoreCase(trimmedName)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Thương hiệu '" + trimmedName + "' đã tồn tại.");
        }

        Brand brand = Brand.builder()
                .name(trimmedName)
                .originCountry(form.originCountry() != null ? form.originCountry().trim() : null)
                .build();

        Brand saved = brandRepository.save(brand);
        log.info("Created brand [id={}, name='{}']", saved.getId(), saved.getName());
        return saved.getId();
    }

    @Override
    @Transactional
    public void updateBrand(Long brandId, BrandForm form) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy thương hiệu với ID: " + brandId));

        String trimmedName = form.name() != null ? form.name().trim() : "";
        if (brandRepository.existsByNameIgnoreCaseAndIdNot(trimmedName, brandId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Thương hiệu '" + trimmedName + "' đã tồn tại.");
        }

        brand.setName(trimmedName);
        brand.setOriginCountry(form.originCountry() != null ? form.originCountry().trim() : null);
        brandRepository.save(brand);
        log.info("Updated brand [id={}, name='{}']", brandId, trimmedName);
    }

    @Override
    @Transactional
    public void deleteBrand(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy thương hiệu với ID: " + brandId));

        if (brandRepository.hasStationeries(brandId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Không thể xóa thương hiệu '" + brand.getName() + "' vì đang có sản phẩm văn phòng phẩm liên kết.");
        }

        brandRepository.delete(brand);
        log.info("Deleted brand [id={}, name='{}']", brandId, brand.getName());
    }

    @Override
    public Page<Brand> listBrands(String keyword, Pageable pageable) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            return brandRepository.findByNameContainingIgnoreCaseOrderByNameAsc(keyword.trim(), pageable);
        }
        return brandRepository.findAllByOrderByNameAsc(pageable);
    }

    @Override
    public Brand getBrandById(Long brandId) {
        return brandRepository.findById(brandId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy thương hiệu với ID: " + brandId));
    }

    @Override
    public long countStationeriesByBrand(Long brandId) {
        return brandRepository.countStationeriesByBrandId(brandId);
    }

    @Override
    public List<Brand> getAllBrands() {
        return brandRepository.findAllByOrderByNameAsc();
    }

    // =========================================================================
    // UC20: CATEGORIES MANAGEMENT (SCR-M03d)
    // =========================================================================

    @Override
    @Transactional
    public Long createCategory(CategoryForm form) {
        String trimmedName = form.name() != null ? form.name().trim() : "";
        Category parent = null;
        if (form.parentId() != null) {
            parent = categoryRepository.findById(form.parentId())
                    .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy danh mục cha với ID: " + form.parentId()));
            if (categoryRepository.existsByNameIgnoreCaseAndParentId(trimmedName, form.parentId())) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Danh mục '" + trimmedName + "' đã tồn tại trong nhóm này.");
            }
        } else {
            if (categoryRepository.existsByNameIgnoreCaseAndParentIsNull(trimmedName)) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Danh mục gốc '" + trimmedName + "' đã tồn tại.");
            }
        }

        Category category = Category.builder()
                .name(trimmedName)
                .parent(parent)
                .description(form.description() != null ? form.description().trim() : null)
                .isActive(form.active() != null ? form.active() : true)
                .build();

        Category saved = categoryRepository.save(category);
        log.info("Created category [id={}, name='{}']", saved.getId(), saved.getName());
        return saved.getId();
    }

    @Override
    @Transactional
    public void updateCategory(Long categoryId, CategoryForm form) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy danh mục với ID: " + categoryId));

        String trimmedName = form.name() != null ? form.name().trim() : "";
        Category parent = null;
        if (form.parentId() != null) {
            if (form.parentId().equals(categoryId)) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Một danh mục không thể nhận chính nó làm danh mục cha.");
            }

            parent = categoryRepository.findById(form.parentId())
                    .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy danh mục cha với ID: " + form.parentId()));

            // Cycle detection: traverse ancestors of target parent to verify current category is not among them
            Category ancestor = parent;
            while (ancestor != null) {
                if (ancestor.getId().equals(categoryId)) {
                    throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                            "Không thể chọn danh mục con/cháu làm danh mục cha (phát hiện chu trình vòng lặp).");
                }
                ancestor = ancestor.getParent();
            }
        }

        category.setName(trimmedName);
        category.setParent(parent);
        category.setDescription(form.description() != null ? form.description().trim() : null);
        if (form.active() != null) {
            category.setIsActive(form.active());
        }
        categoryRepository.save(category);
        log.info("Updated category [id={}, name='{}']", categoryId, trimmedName);
    }

    @Override
    @Transactional
    public void deleteCategory(Long categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy danh mục với ID: " + categoryId));

        if (categoryRepository.existsByParentId(categoryId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Không thể xóa danh mục vì đang chứa các danh mục con.");
        }

        if (categoryRepository.hasProducts(categoryId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Không thể xóa danh mục vì đang chứa sản phẩm liên kết.");
        }

        categoryRepository.delete(category);
        log.info("Deleted category [id={}, name='{}']", categoryId, category.getName());
    }

    @Override
    public List<Category> getAllCategoriesWithParent() {
        return categoryRepository.findAllWithParent();
    }

    @Override
    public List<Category> getAllActiveCategories() {
        return categoryRepository.findAllByIsActiveTrueOrderByNameAsc();
    }

    @Override
    public long countProductsByCategory(Long categoryId) {
        return categoryRepository.countProductsByCategoryId(categoryId);
    }

    @Override
    @Transactional
    public void toggleCategoryStatus(Long categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy danh mục với ID: " + categoryId));
        category.setIsActive(!category.getIsActive());
        categoryRepository.save(category);
        log.info("Toggled category status [id={}, active={}]", categoryId, category.getIsActive());
    }
}
