package com.ddagtech.aureliabooks.repository.specification;

import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.Stationery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * FND-02. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending.
 */
public abstract class ProductSpecification implements Specification<Product> {
    // TODO FND-02: Duy implements typed filter factories, predicates and joins.
    // Abstract by design: no always-true predicate pretending filtering is complete.

    /**
     * Filters products by keyword matching product title or barcode (case-insensitive).
     *
     * @param keyword search keyword
     * @return Specification matching products whose title or barcode contains keyword, or conjunction if blank/null
     */
    public static Specification<Product> hasKeyword(String keyword) {
        return (root, query, cb) -> {
            if (keyword == null || keyword.isBlank()) {
                return cb.conjunction();
            }
            String pattern = "%" + keyword.trim().toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("title")), pattern),
                    cb.like(cb.lower(root.get("barcode")), pattern)
            );
        };
    }

    /**
     * Filters products belonging to the specified category IDs (including child and descendant categories).
     *
     * @param categoryIds collection of category IDs to match against
     * @return Specification matching products in the category hierarchy, or conjunction if empty/null
     */
    public static Specification<Product> hasCategoryIn(Collection<Long> categoryIds) {
        return (root, query, cb) -> {
            if (categoryIds == null || categoryIds.isEmpty()) {
                return cb.conjunction();
            } else {
                return root.get("category").get("id").in(categoryIds);
            }
        };
    }

    /**
     * Filters products within a specific price range.
     * Supports filtering by both bounds, only minimum price, or only maximum price.
     *
     * @param minPrice minimum product price boundary (inclusive), or null if unbounded
     * @param maxPrice maximum product price boundary (inclusive), or null if unbounded
     * @return Specification matching the price range, or conjunction if both bounds are null
     */
    public static Specification<Product> priceBetween(BigDecimal minPrice, BigDecimal maxPrice) {
        return (root, query, cb) -> {
            if (minPrice != null && maxPrice != null) {
                return cb.between(root.get("price"), minPrice, maxPrice);
            } else if (minPrice != null) {
                return cb.greaterThanOrEqualTo(root.get("price"), minPrice);
            } else if (maxPrice != null) {
                return cb.lessThanOrEqualTo(root.get("price"), maxPrice);
            } else {
                return cb.conjunction();
            }
        };
    }

    /**
     * Filters book products by their cover type (e.g., PAPERBACK, HARDCOVER) using a subquery.
     *
     * @param coverType book cover binding format
     * @return Specification matching books with the given cover type, or conjunction if null
     */
    public static Specification<Product> hasCoverType(Book.CoverType coverType) {
        return (root, query, cb) -> {
            if (coverType == null) {
                return cb.conjunction();
            } else {
                Subquery<Long> subquery = query.subquery(Long.class);
                Root<Book> bookRoot = subquery.from(Book.class);
                subquery.select(bookRoot.get("productId"));
                subquery.where(cb.equal(bookRoot.get("coverType"), coverType));
                return root.get("id").in(subquery);
            }
        };
    }

    /**
     * Filters book products published by a specific publisher using a subquery.
     *
     * @param publisherId unique identifier of the publisher
     * @return Specification matching books from the publisher, or conjunction if null
     */
    public static Specification<Product> hasPublisher(Long publisherId) {
        return (root, query, cb) -> {
            if (publisherId == null) {
                return cb.conjunction();
            }
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<Book> bookRoot = subquery.from(Book.class);
            subquery.select(bookRoot.get("productId"));
            subquery.where(cb.equal(bookRoot.get("publisher").get("id"),publisherId));
            return root.get("id").in(subquery);
        };
    }

    /**
     * Filters book products written by a specific author via a subquery with inner join on book_authors.
     * Prevents Cartesian product duplication when filtering many-to-many relationships.
     *
     * @param authorId unique identifier of the author
     * @return Specification matching books by the specified author, or conjunction if null
     */
    public static Specification<Product> hasAuthor(Long authorId){
        return (root, query, cb)->{
            if(authorId == null){
                return cb.conjunction();
            }
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<Book> bookRoot = subquery.from(Book.class);
            subquery.select(bookRoot.get("productId"));
            Join<Book, Author> authorJoin = bookRoot.join("authors");
            subquery.where(cb.equal(authorJoin.get("id"),authorId));
            return root.get("id").in(subquery);
        };
    }

    /**
     * Filters stationery products manufactured by a specific brand using a subquery.
     *
     * @param brandId unique identifier of the stationery brand
     * @return Specification matching stationeries from the brand, or conjunction if null
     */
    public static Specification<Product> hasBrand(Long brandId){
        return (root, query, cb)->{
            if(brandId==null){
                return cb.conjunction();
            }
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<Stationery> stationeryRoot = subquery.from(Stationery.class);
            subquery.select(stationeryRoot.get("productId"));
            subquery.where(cb.equal(stationeryRoot.get("brand").get("id"),brandId));
            return root.get("id").in(subquery);
        };
    }

    /**
     * Filters products by their active commercial status.
     *
     * @param isActive target active status flag (true for active storefront items, false for archived)
     * @return Specification matching the active flag, or conjunction if null
     */
    public static Specification<Product> isActive(Boolean isActive){
        return (root,query,cb)->{
            if(isActive==null){
                return cb.conjunction();
            }
            return cb.equal(root.get("isActive"),isActive);
        };
    }

    /**
     * Filters products by product type (e.g., BOOK, STATIONERY).
     *
     * @param productType product classification type
     * @return Specification matching the product type, or conjunction if null
     */
    public static Specification<Product> hasProductType(Product.ProductType productType) {
        return (root, query, cb) -> productType == null ? cb.conjunction() : cb.equal(root.get("productType"), productType);
    }

    /**
     * Filters book products by textbook status using a subquery.
     *
     * @param isTextbook flag indicating whether the book is a textbook
     * @return Specification matching books with the textbook flag, or conjunction if null
     */
    public static Specification<Product> isTextbook(Boolean isTextbook) {
        return (root, query, cb) -> {
            if (isTextbook == null) {
                return cb.conjunction();
            }
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<Book> bookRoot = subquery.from(Book.class);
            subquery.select(bookRoot.get("productId"));
            subquery.where(
                    cb.equal(bookRoot.get("productId"), root.get("id")),
                    cb.equal(bookRoot.get("isTextbook"), isTextbook)
            );
            return root.get("id").in(subquery);
        };
    }
}
