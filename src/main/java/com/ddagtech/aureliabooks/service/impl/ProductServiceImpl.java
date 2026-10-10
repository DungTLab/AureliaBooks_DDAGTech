package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.request.ProductFilterRequest;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.dto.response.ProductAutoCompleteResponse;
import com.ddagtech.aureliabooks.dto.response.ProductDetailResponse;
import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.Stationery;
import com.ddagtech.aureliabooks.repository.BookRepository;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.StationeryRepository;
import com.ddagtech.aureliabooks.repository.specification.ProductSpecification;
import com.ddagtech.aureliabooks.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Service implementation for product catalog operations (UC01, UC02, UC03).
 */
@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BookRepository bookRepository;
    private final StationeryRepository stationeryRepository;

    public ProductServiceImpl(ProductRepository productRepository,
                              CategoryRepository categoryRepository,
                              BookRepository bookRepository) {
        this(productRepository, categoryRepository, bookRepository, null);
    }

    @Autowired
    public ProductServiceImpl(ProductRepository productRepository,
                              CategoryRepository categoryRepository,
                              BookRepository bookRepository,
                              StationeryRepository stationeryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.bookRepository = bookRepository;
        this.stationeryRepository = stationeryRepository;
    }

    @Transactional(readOnly = true)
    @Override
    public Page<ProductSummary> browse(ProductFilterRequest filter, Pageable pageable) {
        Specification<Product> spec = ProductSpecification.isActive(true);
        spec = spec.and((root, query, cb) -> cb.greaterThan(root.get("stockQuantity"), 0));

        if (filter != null) {
            if (filter.keyword() != null && !filter.keyword().isBlank()) {
                spec = spec.and(ProductSpecification.hasKeyword(filter.keyword()));
            }
            if (filter.categoryId() != null) {
                List<Long> targetCategoryIds = new ArrayList<>();
                targetCategoryIds.add(filter.categoryId());
                if (categoryRepository != null) {
                    List<Category> children = categoryRepository.findByParentId(filter.categoryId());
                    if (children != null && !children.isEmpty()) {
                        for (Category child : children) {
                            targetCategoryIds.add(child.getId());
                        }
                    }
                }
                spec = spec.and(ProductSpecification.hasCategoryIn(targetCategoryIds));
            }
            if (filter.minPrice() != null || filter.maxPrice() != null) {
                spec = spec.and(ProductSpecification.priceBetween(filter.minPrice(), filter.maxPrice()));
            }
            if (filter.authorId() != null) {
                spec = spec.and(ProductSpecification.hasAuthor(filter.authorId()));
            }
            if (filter.publisherId() != null) {
                spec = spec.and(ProductSpecification.hasPublisher(filter.publisherId()));
            }
            if (filter.brandId() != null) {
                spec = spec.and(ProductSpecification.hasBrand(filter.brandId()));
            }
            if (filter.coverType() != null) {
                spec = spec.and(ProductSpecification.hasCoverType(filter.coverType()));
            }
            if (filter.productType() != null) {
                spec = spec.and(ProductSpecification.hasProductType(filter.productType()));
            }
            if (filter.isTextbook() != null) {
                spec = spec.and(ProductSpecification.isTextbook(filter.isTextbook()));
            }
        }

        Page<Product> productPage = productRepository.findAll(spec, pageable);

        // Batch fetch book details in a single query to eliminate N+1 problem
        List<Long> bookProductIds = new ArrayList<>();
        for (Product p : productPage) {
            if (p.getProductType() == Product.ProductType.BOOK) {
                bookProductIds.add(p.getId());
            }
        }

        Map<Long, Book> bookMap = new HashMap<>();
        if (!bookProductIds.isEmpty()) {
            for (Book book : bookRepository.findAllById(bookProductIds)) {
                bookMap.put(book.getProductId(), book);
            }
        }

        return productPage.map(p -> {
            String seriesName = null;
            Integer volumeNumber = null;
            if (p.getProductType() == Product.ProductType.BOOK) {
                Book book = bookMap.get(p.getId());
                if (book != null) {
                    seriesName = book.getSeriesName();
                    volumeNumber = book.getVolumeNumber();
                }
            }
            return new ProductSummary(
                    p.getId(),
                    p.getTitle(),
                    p.getPrice(),
                    p.getStockQuantity(),
                    p.getMainImageUrl(),
                    p.getProductType(),
                    seriesName,
                    volumeNumber
            );
        });
    }

    @Transactional(readOnly = true)
    @Override
    public ProductDetailResponse viewDetail(Long productId) {
        Product product = productRepository.findById(productId)
                .filter(Product::getIsActive)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + productId));

        String categoryName = product.getCategory() != null ? product.getCategory().getName() : null;
        Long categoryId = product.getCategory() != null ? product.getCategory().getId() : null;

        if (product.getProductType() == Product.ProductType.BOOK) {
            var bookOpt = bookRepository.findById(productId);
            if (bookOpt.isPresent()) {
                Book book = bookOpt.get();
                String publisherName = book.getPublisher() != null ? book.getPublisher().getName() : null;
                Long publisherId = book.getPublisher() != null ? book.getPublisher().getId() : null;
                List<String> authorNames = book.getAuthors() != null
                        ? book.getAuthors().stream().map(Author::getName).sorted().toList()
                        : List.of();

                return new ProductDetailResponse(
                        product.getId(),
                        product.getBarcode(),
                        product.getTitle(),
                        product.getPrice(),
                        product.getOriginalCost(),
                        product.getStockQuantity(),
                        product.getMainImageUrl(),
                        product.getDescription(),
                        product.getWeightGrams(),
                        product.getProductType(),
                        categoryId,
                        categoryName,
                        book.getIsbn(),
                        publisherId,
                        publisherName,
                        authorNames,
                        book.getSeriesName(),
                        book.getVolumeNumber(),
                        book.getIsTextbook(),
                        book.getPublicationYear(),
                        book.getEdition(),
                        book.getPageCount(),
                        book.getCoverType(),
                        book.getLanguage(),
                        null,
                        null,
                        null,
                        null,
                        null
                );
            }
        } else if (product.getProductType() == Product.ProductType.STATIONERY && stationeryRepository != null) {
            var stationeryOpt = stationeryRepository.findById(productId);
            if (stationeryOpt.isPresent()) {
                Stationery stationery = stationeryOpt.get();
                String brandName = stationery.getBrand() != null ? stationery.getBrand().getName() : null;
                Long brandId = stationery.getBrand() != null ? stationery.getBrand().getId() : null;

                return new ProductDetailResponse(
                        product.getId(),
                        product.getBarcode(),
                        product.getTitle(),
                        product.getPrice(),
                        product.getOriginalCost(),
                        product.getStockQuantity(),
                        product.getMainImageUrl(),
                        product.getDescription(),
                        product.getWeightGrams(),
                        product.getProductType(),
                        categoryId,
                        categoryName,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        brandId,
                        brandName,
                        stationery.getMaterial(),
                        stationery.getColor(),
                        stationery.getWarrantyMonths()
                );
            }
        }

        return new ProductDetailResponse(
                product.getId(),
                product.getBarcode(),
                product.getTitle(),
                product.getPrice(),
                product.getOriginalCost(),
                product.getStockQuantity(),
                product.getMainImageUrl(),
                product.getDescription(),
                product.getWeightGrams(),
                product.getProductType(),
                categoryId,
                categoryName,
                null, null, null, List.of(), null, null, null, null, null, null, null, null,
                null, null, null, null, null
        );
    }

    @Override
    public List<CategorySummary> getActiveCategories() {
        return categoryRepository.findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .map(c -> new CategorySummary(
                        c.getId(),
                        c.getName(),
                        c.getParent() != null ? c.getParent().getId() : null
                )).toList();
    }

    @Override
    public List<ProductAutoCompleteResponse> autocomplete(String keyword) {
        if(keyword == null || keyword.trim().length() < 2){
            return List.of();
        }

        Specification<Product> spec = ProductSpecification.isActive(true)
                .and((root, query, cb) -> cb.greaterThan(root.get("stockQuantity"), 0))
                .and(ProductSpecification.hasKeyWord(keyword.trim()))
                .and(ProductSpecification.prioritizeExactIdentifier(keyword.trim()));

        Pageable limitFive = PageRequest.of(0,5);
        Page<Product> products = productRepository.findAll(spec,limitFive);

        return products.getContent().stream()
                .map(p -> new ProductAutoCompleteResponse(
                        p.getId(),
                        p.getTitle(),
                        p.getPrice(),
                        p.getMainImageUrl(),
                        p.getBarcode()
                )).toList();
    }
}
