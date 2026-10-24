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
import com.ddagtech.aureliabooks.mapper.MasterDataMapper;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Service implementation for managing master taxonomies (UC17: Authors, UC18: Publishers, UC19: Brands, UC20: Categories).
 * Enforces business validation, name uniqueness, relational reference preservation, and tree cycle avoidance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasterDataServiceImpl implements MasterDataService {

    private final AuthorRepository authorRepository;
    private final PublisherRepository publisherRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    private final MasterDataMapper masterDataMapper;
    private final TransactionTemplate transactionTemplate;

    // =========================================================================
    // UC17: AUTHORS
    // =========================================================================

    @Override
    public synchronized Long createAuthor(AuthorForm form) {
        return executeInTransaction(status -> {
            String trimmedName = form.name() != null ? form.name().trim() : "";
            if (authorRepository.existsByNameIgnoreCase(trimmedName)) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tác giả với tên '" + trimmedName + "' đã tồn tại trong hệ thống.");
            }

            Author author = masterDataMapper.toAuthorEntity(form);
            Author saved = authorRepository.saveAndFlush(author);
            log.info("Created new author [id={}, name='{}']", saved.getId(), saved.getName());
            return saved.getId();
        });
    }

    @Override
    public synchronized void updateAuthor(Long authorId, AuthorForm form) {
        executeInTransaction(status -> {
            Author author = authorRepository.findById(authorId)
                    .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy tác giả với ID: " + authorId));

            String trimmedName = form.name() != null ? form.name().trim() : "";
            if (authorRepository.existsByNameIgnoreCaseAndIdNot(trimmedName, authorId)) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tác giả với tên '" + trimmedName + "' đã tồn tại trong hệ thống.");
            }

            masterDataMapper.updateAuthorEntity(author, form);
            authorRepository.saveAndFlush(author);
            log.info("Updated author [id={}, name='{}']", authorId, trimmedName);
            return null;
        });
    }

    private <T> T executeInTransaction(TransactionCallback<T> action) {
        if (transactionTemplate != null) {
            return transactionTemplate.execute(action);
        }
        return action.doInTransaction(null);
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

    @Override
    @Transactional
    public void toggleAuthorStatus(Long authorId) {
        Author author = authorRepository.findById(authorId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy tác giả với ID: " + authorId));
        author.setIsActive(!Boolean.TRUE.equals(author.getIsActive()));
        authorRepository.save(author);
        log.info("Toggled author status [id={}, active={}]", authorId, author.getIsActive());
    }

    // =========================================================================
    // UC18: PUBLISHERS
    // =========================================================================

    @Override
    @Transactional
    public Long createPublisher(PublisherForm form) {
        String trimmedName = form.name() != null ? form.name().trim() : "";
        if (publisherRepository.existsByNameIgnoreCase(trimmedName)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Nhà xuất bản '" + trimmedName + "' đã tồn tại.");
        }

        Publisher publisher = masterDataMapper.toPublisherEntity(form);
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

        masterDataMapper.updatePublisherEntity(publisher, form);
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
    public List<Publisher> getAllPublishers() {
        return publisherRepository.findAllByOrderByNameAsc();
    }

    // =========================================================================
    // UC19: BRANDS
    // =========================================================================

    @Override
    @Transactional
    public Long createBrand(BrandForm form) {
        String trimmedName = form.name() != null ? form.name().trim() : "";
        if (brandRepository.existsByNameIgnoreCase(trimmedName)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Thương hiệu '" + trimmedName + "' đã tồn tại.");
        }

        Brand brand = masterDataMapper.toBrandEntity(form);
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

        masterDataMapper.updateBrandEntity(brand, form);
        brandRepository.save(brand);
        log.info("Updated brand [id={}, name='{}']", brandId, trimmedName);
    }

    @Override
    @Transactional
    public void deleteBrand(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy thương hiệu với ID: " + brandId));

        // Note: brand reference check in stationeries
        brandRepository.delete(brand);
        log.info("Deleted brand [id={}, name='{}']", brandId, brand.getName());
    }

    @Override
    public List<Brand> getAllBrands() {
        return brandRepository.findAllByOrderByNameAsc();
    }

    // =========================================================================
    // UC20: CATEGORIES
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

        Category category = masterDataMapper.toCategoryEntity(form, parent);
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
                if (categoryRepository.existsByNameIgnoreCaseAndParentIdAndIdNot(trimmedName, form.parentId(), categoryId)) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Danh mục con '" + trimmedName + "' đã tồn tại dưới danh mục cha đã chọn.");
            }

            // Cycle detection: traverse ancestors of target parent to verify current category is not among them
            Category ancestor = parent;
            while (ancestor != null) {
                if (ancestor.getId().equals(categoryId)) {
                    throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                            "Không thể chọn danh mục con/cháu làm danh mục cha (phát hiện chu trình vòng lặp).");
                }
                ancestor = ancestor.getParent();
            }
        } else {
            if (categoryRepository.existsByNameIgnoreCaseAndParentIsNullAndIdNot(trimmedName, categoryId)) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Danh mục gốc '" + trimmedName + "' đã tồn tại.");
            }
        }

        masterDataMapper.updateCategoryEntity(category, form, parent);
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
    public List<Category> getAllActiveCategories() {
        return categoryRepository.findAllByIsActiveTrueOrderByNameAsc();
    }
}
