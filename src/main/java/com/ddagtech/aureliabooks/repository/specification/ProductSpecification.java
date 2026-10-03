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
import java.security.spec.RSAOtherPrimeInfo;
import java.util.Collection;

/**
 * FND-02. Owner: Huỳnh Nhật Duy. Sprint 1 scaffold; business implementation pending.
 */
public abstract class ProductSpecification implements Specification<Product> {
    // TODO FND-02: Duy implements typed filter factories, predicates and joins.
    // Abstract by design: no always-true predicate pretending filtering is complete.

    public static Specification<Product> hasCategoryIn(Collection<Long> categoryIds) {
        return (root, query, cb) -> {
            if (categoryIds == null || categoryIds.isEmpty()) {
                return cb.conjunction();
            } else {
                return root.get("category").get("id").in(categoryIds);
            }
        };
    }

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

    public static Specification<Product> isActive(Boolean isActive){
        return (root,query,cb)->{
            if(isActive==null){
                return cb.conjunction();
            }
            return cb.equal(root.get("isActive"),isActive);
        };
    }
}
