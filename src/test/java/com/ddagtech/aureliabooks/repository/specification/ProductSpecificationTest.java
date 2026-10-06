package com.ddagtech.aureliabooks.repository.specification;

import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.Stationery;
import jakarta.persistence.criteria.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test suite for ProductSpecification dynamic criteria predicates (FND-02).
 * Verifies category hierarchy filter, price range bounds, table-per-type attributes
 * (cover type, publisher, author, brand), and active commercial status.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("unchecked")
class ProductSpecificationTest {

    @Mock
    private Root<Product> root;

    @Mock
    private CriteriaQuery<?> query;

    @Mock
    private CriteriaBuilder cb;

    @Mock
    private Predicate conjunctionPredicate;

    @Mock
    private Predicate simplePredicate;

    @Mock
    private Path<Object> categoryPath;

    @Mock
    private Path<Object> categoryIdPath;

    @Mock
    private Path<BigDecimal> pricePath;

    @Mock
    private Path<Boolean> isActivePath;

    @Mock
    private Path<Long> idPath;

    @Mock
    private Subquery<Long> subquery;

    @Mock
    private Root<Book> bookRoot;

    @Mock
    private Root<Stationery> stationeryRoot;

    @BeforeEach
    void setUp() {
        when(cb.conjunction()).thenReturn(conjunctionPredicate);
    }

    @Nested
    @DisplayName("Category filtering tests")
    class CategoryFilterTests {

        @Test
        @DisplayName("Should return conjunction when category IDs collection is null or empty")
        void shouldReturnConjunctionWhenCategoryIdsNullOrEmpty() {
            Specification<Product> specNull = ProductSpecification.hasCategoryIn(null);
            Specification<Product> specEmpty = ProductSpecification.hasCategoryIn(Collections.emptyList());

            Predicate predNull = specNull.toPredicate(root, query, cb);
            Predicate predEmpty = specEmpty.toPredicate(root, query, cb);

            assertThat(predNull).isSameAs(conjunctionPredicate);
            assertThat(predEmpty).isSameAs(conjunctionPredicate);
        }

        @Test
        @DisplayName("Should build IN predicate when valid category IDs are provided")
        void shouldBuildInPredicateForValidCategories() {
            List<Long> categoryIds = List.of(1L, 2L, 3L);
            when(root.get("category")).thenReturn(categoryPath);
            when(categoryPath.get("id")).thenReturn(categoryIdPath);
            when(categoryIdPath.in(categoryIds)).thenReturn(simplePredicate);

            Specification<Product> spec = ProductSpecification.hasCategoryIn(categoryIds);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(simplePredicate);
            verify(categoryIdPath).in(categoryIds);
        }
    }

    @Nested
    @DisplayName("Price range filtering tests")
    class PriceRangeFilterTests {

        @Test
        @DisplayName("Should return conjunction when both price bounds are null")
        void shouldReturnConjunctionWhenBothBoundsNull() {
            Specification<Product> spec = ProductSpecification.priceBetween(null, null);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(conjunctionPredicate);
        }

        @Test
        @DisplayName("Should build between predicate when both min and max prices are specified")
        void shouldBuildBetweenPredicateWhenBothBoundsPresent() {
            BigDecimal min = new BigDecimal("50000");
            BigDecimal max = new BigDecimal("200000");
            when(root.<BigDecimal>get("price")).thenReturn(pricePath);
            when(cb.between(pricePath, min, max)).thenReturn(simplePredicate);

            Specification<Product> spec = ProductSpecification.priceBetween(min, max);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(simplePredicate);
            verify(cb).between(pricePath, min, max);
        }

        @Test
        @DisplayName("Should build greaterThanOrEqualTo predicate when only min price is specified")
        void shouldBuildGtePredicateWhenOnlyMinPricePresent() {
            BigDecimal min = new BigDecimal("100000");
            when(root.<BigDecimal>get("price")).thenReturn(pricePath);
            when(cb.greaterThanOrEqualTo(pricePath, min)).thenReturn(simplePredicate);

            Specification<Product> spec = ProductSpecification.priceBetween(min, null);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(simplePredicate);
            verify(cb).greaterThanOrEqualTo(pricePath, min);
        }

        @Test
        @DisplayName("Should build lessThanOrEqualTo predicate when only max price is specified")
        void shouldBuildLtePredicateWhenOnlyMaxPricePresent() {
            BigDecimal max = new BigDecimal("500000");
            when(root.<BigDecimal>get("price")).thenReturn(pricePath);
            when(cb.lessThanOrEqualTo(pricePath, max)).thenReturn(simplePredicate);

            Specification<Product> spec = ProductSpecification.priceBetween(null, max);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(simplePredicate);
            verify(cb).lessThanOrEqualTo(pricePath, max);
        }
    }

    @Nested
    @DisplayName("Table-Per-Type subquery filtering tests")
    class TablePerTypeFilterTests {

        @Test
        @DisplayName("Should return conjunction when cover type is null")
        void shouldReturnConjunctionWhenCoverTypeNull() {
            Specification<Product> spec = ProductSpecification.hasCoverType(null);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(conjunctionPredicate);
        }

        @Test
        @DisplayName("Should build subquery filter for book cover type")
        void shouldBuildSubqueryForCoverType() {
            Path coverTypePath = mock(Path.class);
            Path productIdPath = mock(Path.class);
            when(bookRoot.get("coverType")).thenReturn(coverTypePath);
            when(bookRoot.get("productId")).thenReturn(productIdPath);
            when(cb.equal(coverTypePath, Book.CoverType.PAPERBACK)).thenReturn(simplePredicate);
            when(query.subquery(Long.class)).thenReturn(subquery);
            when(subquery.from(Book.class)).thenReturn(bookRoot);
            when(root.get("id")).thenReturn((Path) idPath);
            when(idPath.in(subquery)).thenReturn(simplePredicate);

            Specification<Product> spec = ProductSpecification.hasCoverType(Book.CoverType.PAPERBACK);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(simplePredicate);
            verify(subquery).where(simplePredicate);
        }

        @Test
        @DisplayName("Should return conjunction when authorId is null")
        void shouldReturnConjunctionWhenAuthorIdNull() {
            Specification<Product> spec = ProductSpecification.hasAuthor(null);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(conjunctionPredicate);
        }

        @Test
        @DisplayName("Should build subquery with inner join for author filtering")
        void shouldBuildSubqueryWithJoinForAuthor() {
            Join<Book, Author> authorJoin = mock(Join.class);
            Path authorIdPath = mock(Path.class);
            Path productIdPath = mock(Path.class);
            when(bookRoot.get("productId")).thenReturn(productIdPath);
            when(authorJoin.get("id")).thenReturn(authorIdPath);
            when(cb.equal(authorIdPath, 10L)).thenReturn(simplePredicate);

            when(query.subquery(Long.class)).thenReturn(subquery);
            when(subquery.from(Book.class)).thenReturn(bookRoot);
            when(bookRoot.<Book, Author>join("authors")).thenReturn(authorJoin);
            when(root.get("id")).thenReturn((Path) idPath);
            when(idPath.in(subquery)).thenReturn(simplePredicate);

            Specification<Product> spec = ProductSpecification.hasAuthor(10L);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(simplePredicate);
            verify(subquery).where(simplePredicate);
        }

        @Test
        @DisplayName("Should build subquery for publisher filtering")
        void shouldBuildSubqueryForPublisher() {
            Path publisherPath = mock(Path.class);
            Path publisherIdPath = mock(Path.class);
            Path productIdPath = mock(Path.class);
            when(bookRoot.get("productId")).thenReturn(productIdPath);
            when(bookRoot.get("publisher")).thenReturn(publisherPath);
            when(publisherPath.get("id")).thenReturn(publisherIdPath);
            when(cb.equal(publisherIdPath, 5L)).thenReturn(simplePredicate);

            when(query.subquery(Long.class)).thenReturn(subquery);
            when(subquery.from(Book.class)).thenReturn(bookRoot);
            when(root.get("id")).thenReturn((Path) idPath);
            when(idPath.in(subquery)).thenReturn(simplePredicate);

            Specification<Product> spec = ProductSpecification.hasPublisher(5L);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(simplePredicate);
            verify(subquery).where(simplePredicate);
        }

        @Test
        @DisplayName("Should build subquery for stationery brand filtering")
        void shouldBuildSubqueryForBrand() {
            Path brandPath = mock(Path.class);
            Path brandIdPath = mock(Path.class);
            Path productIdPath = mock(Path.class);
            when(stationeryRoot.get("productId")).thenReturn(productIdPath);
            when(stationeryRoot.get("brand")).thenReturn(brandPath);
            when(brandPath.get("id")).thenReturn(brandIdPath);
            when(cb.equal(brandIdPath, 8L)).thenReturn(simplePredicate);

            when(query.subquery(Long.class)).thenReturn(subquery);
            when(subquery.from(Stationery.class)).thenReturn(stationeryRoot);
            when(root.get("id")).thenReturn((Path) idPath);
            when(idPath.in(subquery)).thenReturn(simplePredicate);

            Specification<Product> spec = ProductSpecification.hasBrand(8L);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(simplePredicate);
            verify(subquery).where(simplePredicate);
        }
    }

    @Nested
    @DisplayName("Active status filtering tests")
    class ActiveStatusFilterTests {

        @Test
        @DisplayName("Should return conjunction when isActive parameter is null")
        void shouldReturnConjunctionWhenIsActiveNull() {
            Specification<Product> spec = ProductSpecification.isActive(null);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(conjunctionPredicate);
        }

        @Test
        @DisplayName("Should build equality predicate when isActive is provided")
        void shouldBuildEqualPredicateWhenIsActiveProvided() {
            when(root.get("isActive")).thenReturn((Path) isActivePath);
            when(cb.equal(isActivePath, true)).thenReturn(simplePredicate);

            Specification<Product> spec = ProductSpecification.isActive(true);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(simplePredicate);
            verify(cb).equal(isActivePath, true);
        }
    }

    @Nested
    @DisplayName("Product type filtering tests")
    class ProductTypeFilterTests {

        @Test
        @DisplayName("Should return conjunction when productType parameter is null")
        void shouldReturnConjunctionWhenProductTypeNull() {
            Specification<Product> spec = ProductSpecification.hasProductType(null);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(conjunctionPredicate);
        }

        @Test
        @DisplayName("Should build equality predicate when productType is provided")
        void shouldBuildEqualPredicateWhenProductTypeProvided() {
            Path productTypePath = mock(Path.class);
            when(root.get("productType")).thenReturn(productTypePath);
            when(cb.equal(productTypePath, Product.ProductType.BOOK)).thenReturn(simplePredicate);

            Specification<Product> spec = ProductSpecification.hasProductType(Product.ProductType.BOOK);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(simplePredicate);
            verify(cb).equal(productTypePath, Product.ProductType.BOOK);
        }
    }

    @Nested
    @DisplayName("Textbook filtering tests")
    class TextbookFilterTests {

        @Test
        @DisplayName("Should return conjunction when isTextbook parameter is null")
        void shouldReturnConjunctionWhenIsTextbookNull() {
            Specification<Product> spec = ProductSpecification.isTextbook(null);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(conjunctionPredicate);
        }

        @Test
        @DisplayName("Should build subquery predicate when isTextbook is provided")
        void shouldBuildSubqueryWhenIsTextbookProvided() {
            Path bookProductIdPath = mock(Path.class);
            Path isTextbookPath = mock(Path.class);
            Predicate idEqualPredicate = mock(Predicate.class);
            Predicate textbookEqualPredicate = mock(Predicate.class);

            when(query.subquery(Long.class)).thenReturn(subquery);
            when(subquery.from(Book.class)).thenReturn(bookRoot);
            when(bookRoot.get("productId")).thenReturn(bookProductIdPath);
            when(bookRoot.get("isTextbook")).thenReturn(isTextbookPath);
            when(root.get("id")).thenReturn((Path) idPath);

            when(cb.equal(bookProductIdPath, (Path) idPath)).thenReturn(idEqualPredicate);
            when(cb.equal(isTextbookPath, true)).thenReturn(textbookEqualPredicate);

            when(root.get("id")).thenReturn((Path) idPath);
            when(idPath.in(subquery)).thenReturn(simplePredicate);

            Specification<Product> spec = ProductSpecification.isTextbook(true);
            Predicate predicate = spec.toPredicate(root, query, cb);

            assertThat(predicate).isSameAs(simplePredicate);
            verify(subquery).where(idEqualPredicate, textbookEqualPredicate);
        }
    }

    @Nested
    @DisplayName("Combined AND specification tests")
    class CombinedSpecificationTests {

        @Test
        @DisplayName("Should combine multiple specifications using conjunction and AND conditions")
        void shouldCombineSpecificationsWithAnd() {
            Predicate pred1 = mock(Predicate.class);
            Predicate pred2 = mock(Predicate.class);
            Predicate combinedPredicate = mock(Predicate.class);

            when(root.get("isActive")).thenReturn((Path) isActivePath);
            when(cb.equal(isActivePath, true)).thenReturn(pred1);

            Path productTypePath = mock(Path.class);
            when(root.get("productType")).thenReturn(productTypePath);
            when(cb.equal(productTypePath, Product.ProductType.BOOK)).thenReturn(pred2);

            when(cb.and(pred1, pred2)).thenReturn(combinedPredicate);

            Specification<Product> combinedSpec = Specification
                    .where(ProductSpecification.isActive(true))
                    .and(ProductSpecification.hasProductType(Product.ProductType.BOOK));

            Predicate result = combinedSpec.toPredicate(root, query, cb);

            assertThat(result).isNotNull();
            verify(cb).and(pred1, pred2);
        }
    }
}
