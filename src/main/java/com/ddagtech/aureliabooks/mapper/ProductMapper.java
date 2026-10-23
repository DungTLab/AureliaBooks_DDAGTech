package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.request.BookForm;
import com.ddagtech.aureliabooks.dto.request.StationeryForm;
import com.ddagtech.aureliabooks.dto.response.AuthorSummary;
import com.ddagtech.aureliabooks.dto.response.ProductAutoCompleteResponse;
import com.ddagtech.aureliabooks.dto.response.ProductDetailResponse;
import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.Stationery;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Stateless mapper for Product, Book, and Stationery conversions (UC01-UC03, UC15-UC17).
 * Enforces BR-01-03 / QC02: never mutates stockQuantity from product forms.
 */
@Component
public class ProductMapper {

    public static ProductSummary toSummary(Product product, Book book) {
        if (product == null) {
            return null;
        }
        boolean isBook = product.getProductType() == Product.ProductType.BOOK && book != null;
        return new ProductSummary(
                product.getId(),
                product.getTitle(),
                product.getPrice(),
                product.getStockQuantity(),
                product.getMainImageUrl(),
                product.getProductType(),
                isBook ? book.getSeriesName() : null,
                isBook ? book.getVolumeNumber() : null
        );
    }

    public ProductAutoCompleteResponse toAutoComplete(Product p) {
        if (p == null) {
            return null;
        }
        return new ProductAutoCompleteResponse(
                p.getId(),
                p.getTitle(),
                p.getPrice(),
                p.getMainImageUrl(),
                p.getBarcode()
        );
    }

    public ProductDetailResponse toBookDetail(Product p, Book b) {
        if (p == null) {
            return null;
        }
        String categoryName = p.getCategory() != null ? p.getCategory().getName() : null;
        Long categoryId = p.getCategory() != null ? p.getCategory().getId() : null;

        if (b == null) {
            return toGenericDetail(p);
        }

        String publisherName = b.getPublisher() != null ? b.getPublisher().getName() : null;
        Long publisherId = b.getPublisher() != null ? b.getPublisher().getId() : null;

        List<String> authorNames = b.getAuthors() != null
                ? b.getAuthors().stream().map(Author::getName).sorted().toList()
                : List.of();
        List<AuthorSummary> authors = b.getAuthors() != null
                ? b.getAuthors().stream()
                        .map(a -> new AuthorSummary(a.getId(), a.getName()))
                        .sorted(Comparator.comparing(AuthorSummary::name))
                        .toList()
                : List.of();

        return new ProductDetailResponse(
                p.getId(),
                p.getBarcode(),
                p.getTitle(),
                p.getPrice(),
                p.getOriginalCost(),
                p.getStockQuantity(),
                p.getMainImageUrl(),
                p.getDescription(),
                p.getWeightGrams(),
                p.getProductType(),
                categoryId,
                categoryName,
                b.getIsbn(),
                publisherId,
                publisherName,
                authorNames,
                b.getSeriesName(),
                b.getVolumeNumber(),
                b.getIsTextbook(),
                b.getPublicationYear(),
                b.getEdition(),
                b.getPageCount(),
                b.getCoverType(),
                b.getLanguage(),
                null,
                null,
                null,
                null,
                null,
                authors
        );
    }

    public ProductDetailResponse toStationeryDetail(Product p, Stationery s) {
        if (p == null) {
            return null;
        }
        if (s == null) {
            return toGenericDetail(p);
        }
        String categoryName = p.getCategory() != null ? p.getCategory().getName() : null;
        Long categoryId = p.getCategory() != null ? p.getCategory().getId() : null;
        String brandName = s.getBrand() != null ? s.getBrand().getName() : null;
        Long brandId = s.getBrand() != null ? s.getBrand().getId() : null;

        return new ProductDetailResponse(
                p.getId(),
                p.getBarcode(),
                p.getTitle(),
                p.getPrice(),
                p.getOriginalCost(),
                p.getStockQuantity(),
                p.getMainImageUrl(),
                p.getDescription(),
                p.getWeightGrams(),
                p.getProductType(),
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
                s.getMaterial(),
                s.getColor(),
                s.getWarrantyMonths()
        );
    }

    public ProductDetailResponse toGenericDetail(Product p) {
        if (p == null) {
            return null;
        }
        String categoryName = p.getCategory() != null ? p.getCategory().getName() : null;
        Long categoryId = p.getCategory() != null ? p.getCategory().getId() : null;
        return new ProductDetailResponse(
                p.getId(),
                p.getBarcode(),
                p.getTitle(),
                p.getPrice(),
                p.getOriginalCost(),
                p.getStockQuantity(),
                p.getMainImageUrl(),
                p.getDescription(),
                p.getWeightGrams(),
                p.getProductType(),
                categoryId,
                categoryName,
                null, null, null, List.of(), null, null, null, null, null, null, null, null,
                null, null, null, null, null
        );
    }

    /**
     * Creates a new Product entity for a Book without setting stockQuantity (BR-01-03 / QC02).
     * Stock quantity is strictly initialized to 0.
     */
    public Product toNewBookProduct(BookForm form, Category category) {
        if (form == null) {
            return null;
        }
        return Product.builder()
                .barcode(form.barcode() != null ? form.barcode().strip() : null)
                .title(form.title() != null ? form.title().strip() : null)
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
    }

    /**
     * Updates common product fields from a BookForm without modifying stockQuantity (BR-01-03 / QC02).
     */
    public void updateBookProductFields(Product target, BookForm form, Category category) {
        if (target == null || form == null) {
            return;
        }
        target.setBarcode(form.barcode() != null ? form.barcode().strip() : null);
        target.setTitle(form.title() != null ? form.title().strip() : null);
        target.setCategory(category);
        target.setPrice(form.price());
        target.setOriginalCost(form.originalCost());
        target.setWeightGrams(form.weightGrams() != null ? form.weightGrams() : (target.getWeightGrams() != null ? target.getWeightGrams() : 200));
        target.setMainImageUrl(form.mainImageUrl());
        target.setTags(form.tags());
        target.setDescription(form.description());
        // STRICT INVARIANT (BR-01-03 / QC02): Never set stockQuantity from Product forms.
    }

    /**
     * Creates a new Product entity for Stationery without setting stockQuantity (BR-01-03 / QC02).
     * Stock quantity is strictly initialized to 0.
     */
    public Product toNewStationeryProduct(StationeryForm form, Category category) {
        if (form == null) {
            return null;
        }
        return Product.builder()
                .barcode(form.barcode() != null ? form.barcode().strip() : null)
                .title(form.title() != null ? form.title().strip() : null)
                .category(category)
                .productType(Product.ProductType.STATIONERY)
                .price(form.price())
                .originalCost(form.originalCost())
                .weightGrams(form.weightGrams() != null ? form.weightGrams() : 100)
                .stockQuantity(0)
                .isActive(true)
                .mainImageUrl(form.mainImageUrl())
                .tags(form.tags())
                .description(form.description())
                .build();
    }

    /**
     * Updates common product fields from a StationeryForm without modifying stockQuantity (BR-01-03 / QC02).
     */
    public void updateStationeryProductFields(Product target, StationeryForm form, Category category) {
        if (target == null || form == null) {
            return;
        }
        target.setBarcode(form.barcode() != null ? form.barcode().strip() : null);
        target.setTitle(form.title() != null ? form.title().strip() : null);
        target.setCategory(category);
        target.setPrice(form.price());
        target.setOriginalCost(form.originalCost());
        target.setWeightGrams(form.weightGrams() != null ? form.weightGrams() : (target.getWeightGrams() != null ? target.getWeightGrams() : 200));
        target.setMainImageUrl(form.mainImageUrl());
        target.setTags(form.tags());
        target.setDescription(form.description());
        // STRICT INVARIANT (BR-01-03 / QC02): Never set stockQuantity from Product forms.
    }
}
