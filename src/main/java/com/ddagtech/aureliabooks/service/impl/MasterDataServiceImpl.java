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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    // UC17: AUTHORS MANAGEMENT (SCR-M03a)
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
    // UC18: PUBLISHERS MANAGEMENT (SCR-M03b)
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

    @Override
    @Transactional
    public void togglePublisherStatus(Long publisherId) {
        Publisher publisher = publisherRepository.findById(publisherId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy nhà xuất bản với ID: " + publisherId));
        publisher.setIsActive(!Boolean.TRUE.equals(publisher.getIsActive()));
        publisherRepository.save(publisher);
        log.info("Toggled publisher status [id={}, active={}]", publisherId, publisher.getIsActive());
    }

    @Override
    public List<Publisher> getAllActivePublishers() {
        return publisherRepository.findAllByIsActiveTrueOrderByNameAsc();
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

    @Override
    @Transactional
    public void toggleBrandStatus(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy thương hiệu với ID: " + brandId));
        brand.setIsActive(!Boolean.TRUE.equals(brand.getIsActive()));
        brandRepository.save(brand);
        log.info("Toggled brand status [id={}, active={}]", brandId, brand.getIsActive());
    }

    @Override
    public List<Brand> getAllActiveBrands() {
        return brandRepository.findAllByIsActiveTrueOrderByNameAsc();
    }

    // =========================================================================
    // UC20: CATEGORIES MANAGEMENT (SCR-M03d)
    // =========================================================================

    @Override
    @Transactional
    public Long createCategory(CategoryForm form) {
        String trimmedName = form.name() != null ? form.name().trim() : "";
        Category parent = null;
        try {
            if (form.parentId() != null) {
                // Lock root categories exclusively to serialize re-parenting and prevent disjoint multi-node cycles
                categoryRepository.findRootCategoriesWithLock();
                // Lock parent exclusively to serialize sibling creation under the same parent
                parent = categoryRepository.findByIdWithLock(form.parentId())
                        .or(() -> categoryRepository.findById(form.parentId()))
                        .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy danh mục cha với ID: " + form.parentId()));
                if (categoryRepository.existsByNameIgnoreCaseAndParentId(trimmedName, form.parentId())) {
                    throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Danh mục '" + trimmedName + "' đã tồn tại trong nhóm này.");
                }
            } else {
                // Lock root categories to serialize root category creation
                categoryRepository.findRootCategoriesWithLock();
                if (categoryRepository.existsByNameIgnoreCaseAndParentIsNull(trimmedName)) {
                    throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Danh mục gốc '" + trimmedName + "' đã tồn tại.");
                }
            }

            Category category = masterDataMapper.toCategoryEntity(form, parent);
            Category saved = categoryRepository.save(category);
            log.info("Created category [id={}, name='{}']", saved.getId(), saved.getName());
            return saved.getId();
        } catch (PessimisticLockingFailureException | DataIntegrityViolationException e) {
            log.warn("Concurrent category creation conflict for name='{}': {}", trimmedName, e.getMessage());
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Danh mục '" + trimmedName + "' đã tồn tại hoặc đang bị xung đột với giao dịch khác.");
        }
    }

    @Override
    @Transactional
    public void updateCategory(Long categoryId, CategoryForm form) {
        if (form.parentId() != null && form.parentId().equals(categoryId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Một danh mục không thể nhận chính nó làm danh mục cha.");
        }

        String trimmedName = form.name() != null ? form.name().trim() : "";
        Category category;
        Category parent = null;

        try {
            if (form.parentId() != null) {
                // Lock root categories exclusively to serialize re-parenting and prevent disjoint multi-node cycles
                categoryRepository.findRootCategoriesWithLock();
                // Lock both categoryId and parentId in deterministic ascending ID order to prevent deadlocks during concurrent re-parenting
                Long firstId = Math.min(categoryId, form.parentId());
                Long secondId = Math.max(categoryId, form.parentId());

                Category first = categoryRepository.findByIdWithLock(firstId)
                        .or(() -> categoryRepository.findById(firstId))
                        .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                                firstId.equals(categoryId) ? "Không tìm thấy danh mục với ID: " + categoryId : "Không tìm thấy danh mục cha với ID: " + form.parentId()));

                Category second = categoryRepository.findByIdWithLock(secondId)
                        .or(() -> categoryRepository.findById(secondId))
                        .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                                secondId.equals(categoryId) ? "Không tìm thấy danh mục với ID: " + categoryId : "Không tìm thấy danh mục cha với ID: " + form.parentId()));

                category = categoryId.equals(firstId) ? first : second;
                parent = form.parentId().equals(firstId) ? first : second;

                if (categoryRepository.existsByNameIgnoreCaseAndParentIdAndIdNot(trimmedName, form.parentId(), categoryId)) {
                    throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Danh mục con '" + trimmedName + "' đã tồn tại dưới danh mục cha đã chọn.");
                }

                // Cycle detection: traverse ancestors of target parent with visited set to verify categoryId is not in path
                Category ancestor = parent;
                Set<Long> visited = new HashSet<>();
                visited.add(categoryId);
                while (ancestor != null) {
                    if (!visited.add(ancestor.getId())) {
                        throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                                "Không thể chọn danh mục con/cháu làm danh mục cha (phát hiện chu trình vòng lặp).");
                    }
                    Category parentEntity = ancestor.getParent();
                    ancestor = (parentEntity != null)
                            ? categoryRepository.findById(parentEntity.getId()).orElse(parentEntity)
                            : null;
                }
            } else {
                category = categoryRepository.findByIdWithLock(categoryId)
                        .or(() -> categoryRepository.findById(categoryId))
                        .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy danh mục với ID: " + categoryId));
                categoryRepository.findRootCategoriesWithLock();
                if (categoryRepository.existsByNameIgnoreCaseAndParentIsNullAndIdNot(trimmedName, categoryId)) {
                    throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Danh mục gốc '" + trimmedName + "' đã tồn tại.");
                }
            }

            masterDataMapper.updateCategoryEntity(category, form, parent);

            // Re-verify cycle detection right before saving to guard against interleaved concurrent modifications
            if (parent != null) {
                Category recheckAncestor = parent;
                Set<Long> recheckVisited = new HashSet<>();
                recheckVisited.add(categoryId);
                while (recheckAncestor != null) {
                    if (!recheckVisited.add(recheckAncestor.getId())) {
                        throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                                "Không thể chọn danh mục con/cháu làm danh mục cha (phát hiện chu trình vòng lặp).");
                    }
                    Category recheckParent = recheckAncestor.getParent();
                    recheckAncestor = (recheckParent != null)
                            ? categoryRepository.findById(recheckParent.getId()).orElse(recheckParent)
                            : null;
                }
            }

            categoryRepository.save(category);
            log.info("Updated category [id={}, name='{}']", categoryId, trimmedName);
        } catch (PessimisticLockingFailureException | DataIntegrityViolationException e) {
            log.warn("Concurrent category update conflict for id={}: {}", categoryId, e.getMessage());
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Thao tác cập nhật danh mục bị xung đột giao dịch, vui lòng thử lại.");
        }
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
