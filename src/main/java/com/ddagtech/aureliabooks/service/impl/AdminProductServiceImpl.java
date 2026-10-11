package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.BookForm;
import com.ddagtech.aureliabooks.dto.request.StationeryForm;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.service.AdminProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service implementation for managing products in catalog back-office (UC15: Books, UC16: Stationery).
 * Enforces business invariants: Table-Per-Type mapping and strictly non-editable stock quantity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminProductServiceImpl implements AdminProductService {

    private final ProductRepository productRepository;
    private final BookRepository bookRepository;
    private final StationeryRepository stationeryRepository;
    private final CategoryRepository categoryRepository;
    private final PublisherRepository publisherRepository;
    private final AuthorRepository authorRepository;
    private final BrandRepository brandRepository;

    // =========================================================================
    // UC15: BOOK MANAGEMENT
    // =========================================================================

    @Override
    @Transactional
    public Long createBook(BookForm form) {
        String barcode = form.barcode().trim();
        String isbn = form.isbn().trim();

        if (productRepository.existsByBarcodeIgnoreCase(barcode)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã vạch (Barcode) '" + barcode + "' đã tồn tại trong hệ thống.");
        }
        if (bookRepository.existsByIsbnIgnoreCase(isbn)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã ISBN '" + isbn + "' đã tồn tại trong hệ thống.");
        }

        Category category = categoryRepository.findById(form.categoryId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy danh mục với ID: " + form.categoryId()));

        Publisher publisher = publisherRepository.findById(form.publisherId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy nhà xuất bản với ID: " + form.publisherId()));

        Set<Author> authors = new HashSet<>();
        if (form.authorIds() != null && !form.authorIds().isEmpty()) {
            authors.addAll(authorRepository.findAllById(form.authorIds()));
        }

        // 1. Create Base Product (Stock is always initialized to 0, never directly set from form per invariant)
        Product product = Product.builder()
                .barcode(barcode)
                .title(form.title().trim())
                .category(category)
                .productType(Product.ProductType.BOOK)
                .price(form.price())
                .originalCost(form.originalCost())
                .weightGrams(form.weightGrams() != null ? form.weightGrams() : 200)
                .stockQuantity(0)
                .isActive(true)
                .mainImageUrl(form.mainImageUrl())
                .tags(form.tags())
                .description(form.description())
                .build();

        Product savedProduct = productRepository.save(product);

        // 2. Create Book Specific Details
        Book book = Book.builder()
                .productId(savedProduct.getId())
                .product(savedProduct)
                .isbn(isbn)
                .publisher(publisher)
                .authors(authors)
                .seriesName(form.seriesName() != null && !form.seriesName().isBlank() ? form.seriesName().trim() : null)
                .volumeNumber(form.volumeNumber())
                .isTextbook(Boolean.TRUE.equals(form.isTextbook()))
                .publicationYear(form.publicationYear())
                .edition(form.edition())
                .pageCount(form.pageCount())
                .language(form.language() != null && !form.language().isBlank() ? form.language() : "Tiếng Việt")
                .coverType(form.coverType() != null ? form.coverType() : Book.CoverType.PAPERBACK)
                .build();

        bookRepository.save(book);
        log.info("Created book successfully [productId={}, isbn='{}', title='{}']", savedProduct.getId(), isbn, savedProduct.getTitle());
        return savedProduct.getId();
    }

    @Override
    @Transactional
    public void updateBook(Long productId, BookForm form) {
        Book book = bookRepository.findByIdWithDetails(productId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy sách với ID: " + productId));

        Product product = book.getProduct();
        String barcode = form.barcode().trim();
        String isbn = form.isbn().trim();

        if (productRepository.existsByBarcodeIgnoreCaseAndIdNot(barcode, productId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã vạch (Barcode) '" + barcode + "' đã tồn tại trên sản phẩm khác.");
        }
        if (bookRepository.existsByIsbnIgnoreCaseAndProductIdNot(isbn, productId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã ISBN '" + isbn + "' đã tồn tại trên cuốn sách khác.");
        }

        Category category = categoryRepository.findById(form.categoryId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy danh mục với ID: " + form.categoryId()));

        Publisher publisher = publisherRepository.findById(form.publisherId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy nhà xuất bản với ID: " + form.publisherId()));

        Set<Author> authors = new HashSet<>();
        if (form.authorIds() != null && !form.authorIds().isEmpty()) {
            authors.addAll(authorRepository.findAllById(form.authorIds()));
        }

        // Update Base Product fields (PRESERVE stockQuantity)
        product.setBarcode(barcode);
        product.setTitle(form.title().trim());
        product.setCategory(category);
        product.setPrice(form.price());
        product.setOriginalCost(form.originalCost());
        product.setWeightGrams(form.weightGrams() != null ? form.weightGrams() : 200);
        product.setMainImageUrl(form.mainImageUrl());
        product.setTags(form.tags());
        product.setDescription(form.description());
        productRepository.save(product);

        // Update Book details
        book.setIsbn(isbn);
        book.setPublisher(publisher);
        book.setAuthors(authors);
        book.setSeriesName(form.seriesName() != null && !form.seriesName().isBlank() ? form.seriesName().trim() : null);
        book.setVolumeNumber(form.volumeNumber());
        book.setIsTextbook(Boolean.TRUE.equals(form.isTextbook()));
        book.setPublicationYear(form.publicationYear());
        book.setEdition(form.edition());
        book.setPageCount(form.pageCount());
        book.setLanguage(form.language() != null && !form.language().isBlank() ? form.language() : "Tiếng Việt");
        if (form.coverType() != null) {
            book.setCoverType(form.coverType());
        }
        bookRepository.save(book);
        log.info("Updated book [productId={}, title='{}']", productId, product.getTitle());
    }

    @Override
    public BookForm getBookForEdit(Long productId) {
        Book book = bookRepository.findByIdWithDetails(productId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy sách với ID: " + productId));

        Product product = book.getProduct();
        Set<Long> authorIds = book.getAuthors().stream().map(Author::getId).collect(Collectors.toSet());

        return new BookForm(
                product.getId(),
                product.getBarcode(),
                product.getTitle(),
                product.getCategory().getId(),
                product.getPrice(),
                product.getOriginalCost(),
                product.getWeightGrams(),
                product.getMainImageUrl(),
                product.getTags(),
                product.getDescription(),
                book.getIsbn(),
                book.getPublisher().getId(),
                authorIds,
                book.getSeriesName(),
                book.getVolumeNumber(),
                book.getIsTextbook(),
                book.getPublicationYear(),
                book.getEdition(),
                book.getPageCount(),
                book.getLanguage(),
                book.getCoverType()
        );
    }

    // =========================================================================
    // UC16: STATIONERY MANAGEMENT
    // =========================================================================

    @Override
    @Transactional
    public Long createStationery(StationeryForm form) {
        String barcode = form.barcode().trim();
        if (productRepository.existsByBarcodeIgnoreCase(barcode)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã vạch (Barcode) '" + barcode + "' đã tồn tại trong hệ thống.");
        }

        Category category = categoryRepository.findById(form.categoryId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy danh mục với ID: " + form.categoryId()));

        Brand brand = brandRepository.findById(form.brandId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy thương hiệu với ID: " + form.brandId()));

        Product product = Product.builder()
                .barcode(barcode)
                .title(form.title().trim())
                .category(category)
                .productType(Product.ProductType.STATIONERY)
                .price(form.price())
                .originalCost(form.originalCost())
                .weightGrams(form.weightGrams() != null ? form.weightGrams() : 200)
                .stockQuantity(0)
                .isActive(true)
                .mainImageUrl(form.mainImageUrl())
                .tags(form.tags())
                .description(form.description())
                .build();

        Product savedProduct = productRepository.save(product);

        Stationery stationery = Stationery.builder()
                .productId(savedProduct.getId())
                .product(savedProduct)
                .brand(brand)
                .material(form.material())
                .color(form.color())
                .warrantyMonths(form.warrantyMonths() != null ? form.warrantyMonths() : 0)
                .build();

        stationeryRepository.save(stationery);
        log.info("Created stationery [productId={}, title='{}']", savedProduct.getId(), savedProduct.getTitle());
        return savedProduct.getId();
    }

    @Override
    @Transactional
    public void updateStationery(Long productId, StationeryForm form) {
        Stationery stationery = stationeryRepository.findByIdWithDetails(productId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy văn phòng phẩm với ID: " + productId));

        Product product = stationery.getProduct();
        String barcode = form.barcode().trim();

        if (productRepository.existsByBarcodeIgnoreCaseAndIdNot(barcode, productId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mã vạch (Barcode) '" + barcode + "' đã tồn tại trên sản phẩm khác.");
        }

        Category category = categoryRepository.findById(form.categoryId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy danh mục với ID: " + form.categoryId()));

        Brand brand = brandRepository.findById(form.brandId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy thương hiệu với ID: " + form.brandId()));

        product.setBarcode(barcode);
        product.setTitle(form.title().trim());
        product.setCategory(category);
        product.setPrice(form.price());
        product.setOriginalCost(form.originalCost());
        product.setWeightGrams(form.weightGrams() != null ? form.weightGrams() : 200);
        product.setMainImageUrl(form.mainImageUrl());
        product.setTags(form.tags());
        product.setDescription(form.description());
        productRepository.save(product);

        stationery.setBrand(brand);
        stationery.setMaterial(form.material());
        stationery.setColor(form.color());
        stationery.setWarrantyMonths(form.warrantyMonths() != null ? form.warrantyMonths() : 0);
        stationeryRepository.save(stationery);
        log.info("Updated stationery [productId={}, title='{}']", productId, product.getTitle());
    }

    @Override
    public StationeryForm getStationeryForEdit(Long productId) {
        Stationery stationery = stationeryRepository.findByIdWithDetails(productId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy văn phòng phẩm với ID: " + productId));

        Product product = stationery.getProduct();
        return new StationeryForm(
                product.getId(),
                product.getBarcode(),
                product.getTitle(),
                product.getCategory().getId(),
                product.getPrice(),
                product.getOriginalCost(),
                product.getWeightGrams(),
                product.getMainImageUrl(),
                product.getTags(),
                product.getDescription(),
                stationery.getBrand().getId(),
                stationery.getMaterial(),
                stationery.getColor(),
                stationery.getWarrantyMonths()
        );
    }

    // =========================================================================
    // GENERAL CATALOG WORKFLOWS
    // =========================================================================

    @Override
    @Transactional
    public void deactivate(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy sản phẩm với ID: " + productId));
        product.setIsActive(false);
        productRepository.save(product);
        log.info("Deactivated product [id={}]", productId);
    }

    @Override
    @Transactional
    public void toggleActive(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy sản phẩm với ID: " + productId));
        boolean newStatus = !Boolean.TRUE.equals(product.getIsActive());
        product.setIsActive(newStatus);
        productRepository.save(product);
        log.info("Toggled active status for product [id={}, active={}]", productId, newStatus);
    }

    @Override
    public Page<Product> listProducts(Product.ProductType productType, String keyword, Pageable pageable) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            if (productType != null) {
                return productRepository.findByProductTypeAndKeyword(productType, kw, kw, pageable);
            }
            return productRepository.findByTitleContainingIgnoreCaseOrBarcodeContainingIgnoreCase(kw, kw, pageable);
        }

        if (productType != null) {
            return productRepository.findByProductType(productType, pageable);
        }
        return productRepository.findAll(pageable);
    }

    @Override
    public Product getProductById(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy sản phẩm với ID: " + productId));
    }
}
