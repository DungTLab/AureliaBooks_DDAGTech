package com.ddagtech.aureliabooks.service.impl;

import com.ddagtech.aureliabooks.dto.request.ProductFilterRequest;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.repository.BookRepository;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.specification.ProductSpecification;
import com.ddagtech.aureliabooks.service.ProductService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BookRepository bookRepository;

    public ProductServiceImpl(ProductRepository productRepository, CategoryRepository categoryRepository, BookRepository bookRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.bookRepository = bookRepository;
    }

    @Transactional(readOnly = true)
    @Override
    public Page<ProductSummary> browse(ProductFilterRequest filter, Pageable pageable) {
        Specification<Product> spec = ProductSpecification.isActive(true);
        spec = spec.and((root, query, cb) -> cb.greaterThan(root.get("stockQuantity"), 0));
        if (filter != null) {
            if (filter.categoryId() != null) {
                spec = spec.and(ProductSpecification.hasCategoryIn(List.of(filter.categoryId())));
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

    @Override
    public ProductSummary viewDetail(Long productId) {
        // Will implement in UC03
        return null;
    }

    @Override
    public List<CategorySummary> getActiveCategories() {
        return categoryRepository.findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .map(c -> new CategorySummary(
                        c.getId(),
                        c.getName(),
                        c.getParent() !=null ? c.getParent().getId() : null
                )).toList();
    }
}
