package com.ddagtech.aureliabooks.repository.specification;

import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.Stationery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reusable product search, filtering, and sorting specifications.
 */
public abstract class ProductSpecification implements Specification<Product> {

    private static String escapeLike(String input) {
        if (input == null) {
            return "";
        }
        // Escape the escape character first, then treat LIKE wildcards as literal text.
        return input.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * Filters products by matching search keyword against product title or barcode.
     *
     * @param keyword search keyword
     * @return Specification matching title or barcode, or conjunction if null or blank
     */
    public static Specification<Product> hasKeyword(String keyword) {
        return (root, query, cb) -> {
            if (keyword == null || keyword.isBlank()) {
                return cb.conjunction();
            }
            // Match the normalized keyword anywhere in searchable text.
            String escaped = escapeLike(keyword.trim().toLowerCase());
            String pattern = "%" + escaped + "%";
            char escapeChar = '\\';

            var titlePredicate = cb.like(cb.lower(root.get("title")), pattern, escapeChar);
            var barcodePredicate = cb.like(cb.lower(root.get("barcode")), pattern, escapeChar);

            // Search book metadata in a subquery to avoid duplicating outer product rows.
            Subquery<Long> bookSubquery = query.subquery(Long.class);
            Root<Book> bookRoot = bookSubquery.from(Book.class);
            bookSubquery.select(bookRoot.get("productId"));
            Join<Book, Author> authorJoin = bookRoot.join("authors", JoinType.LEFT);
            var publisherJoin = bookRoot.join("publisher", JoinType.LEFT);
            var isbnPredicate = cb.like(cb.lower(bookRoot.get("isbn")), pattern, escapeChar);
            var authorPredicate = cb.like(cb.lower(authorJoin.get("name")), pattern, escapeChar);
            var publisherPredicate = cb.like(cb.lower(publisherJoin.get("name")), pattern, escapeChar);
            bookSubquery.where(cb.or(isbnPredicate, authorPredicate, publisherPredicate));

            // Include brand and material matches for stationery products.
            Subquery<Long> stationerySubquery = query.subquery(Long.class);
            Root<Stationery> stationeryRoot = stationerySubquery.from(Stationery.class);
            stationerySubquery.select(stationeryRoot.get("productId"));
            var brandJoin = stationeryRoot.join("brand", JoinType.LEFT);
            var brandPredicate = cb.like(cb.lower(brandJoin.get("name")), pattern, escapeChar);
            var materialPredicate = cb.like(cb.lower(stationeryRoot.get("material")), pattern, escapeChar);
            stationerySubquery.where(cb.or(brandPredicate, materialPredicate));
            // A match in any supported field is enough to include the product.
            return cb.or(
                    titlePredicate,
                    barcodePredicate,
                    root.get("id").in(bookSubquery),
                    root.get("id").in(stationerySubquery)
            );
        };
    }

    public static Specification<Product> hasKeyWord(String keyword) {
        // Keep the older method spelling compatible with existing callers.
        return hasKeyword(keyword);
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
                // Descendant IDs must already be supplied by the caller.
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
                // Accept reversed bounds by normalizing the inclusive price range.
                BigDecimal lower = minPrice.min(maxPrice);
                BigDecimal upper = minPrice.max(maxPrice);
                return cb.between(root.get("price"), lower, upper);
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
                // Only products with matching book details qualify.
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
            // Resolve the publisher through book details rather than the base product.
            subquery.where(cb.equal(bookRoot.get("publisher").get("id"), publisherId));
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
    public static Specification<Product> hasAuthor(Long authorId) {
        return (root, query, cb) -> {
            if (authorId == null) {
                return cb.conjunction();
            }
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<Book> bookRoot = subquery.from(Book.class);
            subquery.select(bookRoot.get("productId"));
            // Keep the many-to-many author join inside the subquery for stable product paging.
            Join<Book, Author> authorJoin = bookRoot.join("authors");
            subquery.where(cb.equal(authorJoin.get("id"), authorId));
            return root.get("id").in(subquery);
        };
    }

    /**
     * Filters stationery products manufactured by a specific brand using a subquery.
     *
     * @param brandId unique identifier of the stationery brand
     * @return Specification matching stationeries from the brand, or conjunction if null
     */
    public static Specification<Product> hasBrand(Long brandId) {
        return (root, query, cb) -> {
            if (brandId == null) {
                return cb.conjunction();
            }
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<Stationery> stationeryRoot = subquery.from(Stationery.class);
            subquery.select(stationeryRoot.get("productId"));
            // Brand filtering applies only to products with stationery details.
            subquery.where(cb.equal(stationeryRoot.get("brand").get("id"), brandId));
            return root.get("id").in(subquery);
        };
    }

    /**
     * Filters products by their active commercial status.
     *
     * @param isActive target active status flag (true for active storefront items, false for archived)
     * @return Specification matching the active flag, or conjunction if null
     */
    public static Specification<Product> isActive(Boolean isActive) {
        return (root, query, cb) -> {
            if (isActive == null) {
                return cb.conjunction();
            }
            // An explicit false value selects inactive products; null skips this filter.
            return cb.equal(root.get("isActive"), isActive);
        };
    }

    /**
     * Filters products by product type (e.g., BOOK, STATIONERY).
     *
     * @param productType product classification type
     * @return Specification matching the product type, or conjunction if null
     */
    public static Specification<Product> hasProductType(Product.ProductType productType) {
        // Leave the type unrestricted when the caller provides no selection.
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
            // A false flag selects non-textbook books, not stationery products.
            subquery.where(
                    cb.equal(bookRoot.get("productId"), root.get("id")),
                    cb.equal(bookRoot.get("isTextbook"), isTextbook)
            );
            return root.get("id").in(subquery);
        };
    }
    /**
     * Orders query results giving highest priority to exact barcode or ISBN matches.
     *
     * @param keyword search keyword
     * @return Specification prioritizing exact barcode/ISBN matches
     */
    public static Specification<Product> prioritizeExactIdentifier(String keyword) {
        // Apply identifier priority without adding a secondary sort.
        return prioritizeExactIdentifier(keyword, Sort.unsorted());
    }

    /**
     * Orders query results giving highest priority to exact barcode or ISBN matches, followed by user-defined sort order.
     *
     * @param keyword search keyword
     * @param sort user-requested sort order
     * @return Specification prioritizing exact barcode/ISBN matches
     */
    public static Specification<Product> prioritizeExactIdentifier(String keyword, Sort sort) {
        return (root, query, cb) -> {
            if (keyword == null || keyword.isBlank() || Long.class.equals(query.getResultType())) {
                // Do not apply ORDER BY for count queries
                return cb.conjunction();
            }
            String exact = keyword.trim().toLowerCase();

            Subquery<Long> exactIsbnSubquery = query.subquery(Long.class);
            Root<Book> bookRoot = exactIsbnSubquery.from(Book.class);
            exactIsbnSubquery.select(bookRoot.get("productId"));
            // Compare the full ISBN rather than using the partial keyword pattern.
            exactIsbnSubquery.where(cb.equal(cb.lower(bookRoot.get("isbn")), exact));

            var isExactBarcode = cb.equal(cb.lower(root.get("barcode")), exact);
            var isExactIsbn = root.get("id").in(exactIsbnSubquery);

            // Rank exact barcode or ISBN matches before other search results.
            var exactMatchCase = cb.selectCase()
                    .when(cb.or(isExactBarcode, isExactIsbn), 0)
                    .otherwise(1);

            List<Order> orders = new ArrayList<>();
            orders.add(cb.asc(exactMatchCase));

            // Use the requested sort only after the exact-match priority.
            if (sort != null && sort.isSorted()) {
                for (Sort.Order sortOrder : sort) {
                    String property = sortOrder.getProperty();
                    if (sortOrder.isAscending()) {
                        orders.add(cb.asc(root.get(property)));
                    } else {
                        orders.add(cb.desc(root.get(property)));
                    }
                }
            }
            // This specification changes ordering without adding a filtering condition.
            query.orderBy(orders);
            return cb.conjunction();
        };
    }
}
