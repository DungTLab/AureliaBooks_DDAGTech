package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.ProductFilterRequest;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.dto.response.ProductDetailResponse;
import com.ddagtech.aureliabooks.dto.response.ProductSummary;
import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Brand;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.Publisher;
import com.ddagtech.aureliabooks.entity.Stationery;
import com.ddagtech.aureliabooks.repository.BookRepository;
import com.ddagtech.aureliabooks.repository.CategoryRepository;
import com.ddagtech.aureliabooks.repository.ProductRepository;
import com.ddagtech.aureliabooks.repository.StationeryRepository;
import com.ddagtech.aureliabooks.service.impl.ProductServiceImpl;
import com.ddagtech.aureliabooks.util.PageableUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit test suite for {@link ProductServiceImpl} (FND-02, UC01, UC02, UC03).
 * Verifies filter building, pagination delegation, entity-to-DTO mapping, N+1 query elimination, and detail retrieval.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private StationeryRepository stationeryRepository;

    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(productRepository, categoryRepository, bookRepository, stationeryRepository);
    }

    @Test
    @DisplayName("Should browse products with default active status filter and map to ProductSummary")
    void shouldBrowseProductsWithDefaultFilters() {
        // Arrange
        Pageable pageable = PageableUtils.create(1, 12, "newest");
        Product product = Product.builder()
                .id(1L)
                .title("Clean Code")
                .price(new BigDecimal("350000.00"))
                .stockQuantity(15)
                .mainImageUrl("https://example.com/clean-code.jpg")
                .isActive(true)
                .productType(Product.ProductType.BOOK)
                .build();
        Page<Product> productPage = new PageImpl<>(List.of(product), pageable, 1);

        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(productPage);

        // Act
        Page<ProductSummary> result = productService.browse(null, pageable);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
        ProductSummary summary = result.getContent().get(0);
        assertThat(summary.id()).isEqualTo(1L);
        assertThat(summary.title()).isEqualTo("Clean Code");
        assertThat(summary.price()).isEqualByComparingTo(new BigDecimal("350000.00"));
        assertThat(summary.stockQuantity()).isEqualTo(15);
        assertThat(summary.mainImageUrl()).isEqualTo("https://example.com/clean-code.jpg");
        assertThat(summary.productType()).isEqualTo(Product.ProductType.BOOK);

        verify(productRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @DisplayName("Should browse products with comprehensive filters including productType and isTextbook")
    void shouldBrowseProductsWithAllFilters() {
        // Arrange
        Pageable pageable = PageableUtils.create(1, 20, "price_asc");
        ProductFilterRequest filter = new ProductFilterRequest(
                "Java",
                5L,
                new BigDecimal("50000"),
                new BigDecimal("200000"),
                10L,
                20L,
                null,
                Book.CoverType.PAPERBACK,
                Product.ProductType.BOOK,
                true
        );

        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty());

        // Act
        Page<ProductSummary> result = productService.browse(filter, pageable);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.isEmpty()).isTrue();
        verify(productRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @DisplayName("Should browse products with category and its child categories")
    void shouldBrowseWithCategoryAndChildren() {
        // Arrange
        Pageable pageable = PageableUtils.create(1, 12, "newest");
        Category child = new Category();
        child.setId(10L);
        when(categoryRepository.findByParentId(1L)).thenReturn(List.of(child));
        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty());

        ProductFilterRequest filter = new ProductFilterRequest(null, 1L, null, null, null, null, null, null, null, null);

        // Act
        productService.browse(filter, pageable);

        // Assert
        verify(categoryRepository).findByParentId(1L);
        verify(productRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @DisplayName("Should browse products with keyword filter")
    void shouldBrowseWithKeywordFilter() {
        // Arrange
        Pageable pageable = PageableUtils.create(1, 12, "newest");
        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty());

        ProductFilterRequest filter = new ProductFilterRequest("Doraemon", null, null, null, null, null, null, null, null, null);

        // Act
        Page<ProductSummary> result = productService.browse(filter, pageable);

        // Assert
        assertThat(result).isNotNull();
        verify(productRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @DisplayName("Should browse stationery products and map type without book lookups")
    void shouldBrowseStationeryProductsWithoutBookLookups() {
        // Arrange
        Pageable pageable = PageableUtils.create(1, 12, "newest");
        Product stationery = Product.builder()
                .id(2L)
                .title("Bút bi Thiên Long")
                .price(new BigDecimal("5000.00"))
                .stockQuantity(100)
                .mainImageUrl("https://example.com/pen.jpg")
                .isActive(true)
                .productType(Product.ProductType.STATIONERY)
                .build();
        Page<Product> productPage = new PageImpl<>(List.of(stationery), pageable, 1);

        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(productPage);

        // Act
        Page<ProductSummary> result = productService.browse(null, pageable);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        ProductSummary summary = result.getContent().get(0);
        assertThat(summary.id()).isEqualTo(2L);
        assertThat(summary.productType()).isEqualTo(Product.ProductType.STATIONERY);
        assertThat(summary.seriesName()).isNull();
        assertThat(summary.volumeNumber()).isNull();

        verify(bookRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("Should browse products for stationery items with brand filter")
    void shouldBrowseStationeryProductsWithFilter() {
        // Arrange
        Pageable pageable = PageableUtils.create(1, 12, "best_sellers");
        ProductFilterRequest filter = new ProductFilterRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                7L,
                null,
                Product.ProductType.STATIONERY,
                null
        );

        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty());

        // Act
        Page<ProductSummary> result = productService.browse(filter, pageable);

        // Assert
        assertThat(result).isNotNull();
        verify(productRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @DisplayName("Should retrieve active categories and map hierarchy correctly")
    void shouldGetActiveCategories() {
        // Arrange
        Category parentCat = new Category();
        parentCat.setId(1L);
        parentCat.setName("Sách Trong Nước");
        parentCat.setIsActive(true);

        Category childCat = new Category();
        childCat.setId(2L);
        childCat.setName("Văn Học");
        childCat.setIsActive(true);
        childCat.setParent(parentCat);

        Category inactiveCat = new Category();
        inactiveCat.setId(3L);
        inactiveCat.setName("Danh Mục Cũ");
        inactiveCat.setIsActive(false);

        when(categoryRepository.findAll()).thenReturn(List.of(parentCat, childCat, inactiveCat));

        // Act
        List<CategorySummary> activeCategories = productService.getActiveCategories();

        // Assert
        assertThat(activeCategories).hasSize(2);
        assertThat(activeCategories.get(0).id()).isEqualTo(1L);
        assertThat(activeCategories.get(0).parentId()).isNull();
        assertThat(activeCategories.get(1).id()).isEqualTo(2L);
        assertThat(activeCategories.get(1).parentId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should batch fetch book details with findAllById and avoid N+1 queries when browsing books")
    void shouldBatchFetchBooksAndAvoidNPlusOne() {
        // Arrange
        Pageable pageable = PageableUtils.create(1, 12, "newest");
        Product book1 = Product.builder().id(101L).title("Book 1").productType(Product.ProductType.BOOK).price(BigDecimal.valueOf(100000)).stockQuantity(10).build();
        Product book2 = Product.builder().id(102L).title("Book 2").productType(Product.ProductType.BOOK).price(BigDecimal.valueOf(120000)).stockQuantity(15).build();
        Product stationery = Product.builder().id(201L).title("Pen").productType(Product.ProductType.STATIONERY).price(BigDecimal.valueOf(20000)).stockQuantity(50).build();

        Page<Product> productPage = new PageImpl<>(List.of(book1, book2, stationery), pageable, 3);
        when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(productPage);

        Book detail1 = Book.builder().productId(101L).seriesName("Harry Potter").volumeNumber(1).build();
        Book detail2 = Book.builder().productId(102L).seriesName("Harry Potter").volumeNumber(2).build();
        when(bookRepository.findAllById(List.of(101L, 102L))).thenReturn(List.of(detail1, detail2));

        // Act
        Page<ProductSummary> result = productService.browse(null, pageable);

        // Assert
        assertThat(result).hasSize(3);
        ProductSummary summary1 = result.getContent().get(0);
        assertThat(summary1.seriesName()).isEqualTo("Harry Potter");
        assertThat(summary1.volumeNumber()).isEqualTo(1);

        ProductSummary summary2 = result.getContent().get(1);
        assertThat(summary2.seriesName()).isEqualTo("Harry Potter");
        assertThat(summary2.volumeNumber()).isEqualTo(2);

        ProductSummary summary3 = result.getContent().get(2);
        assertThat(summary3.seriesName()).isNull();
        assertThat(summary3.volumeNumber()).isNull();

        // Verify N+1 elimination: findAllById called exactly once, findById never called
        verify(bookRepository, times(1)).findAllById(List.of(101L, 102L));
        verify(bookRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Should retrieve full product detail for a Book (UC03)")
    void shouldViewDetailForBook() {
        // Arrange
        Category cat = new Category();
        cat.setId(5L);
        cat.setName("Văn Học");

        Product product = Product.builder()
                .id(1L)
                .barcode("ISBN-978123")
                .title("Harry Potter Tập 1")
                .price(BigDecimal.valueOf(150000))
                .originalCost(BigDecimal.valueOf(200000))
                .stockQuantity(20)
                .mainImageUrl("http://image.jpg")
                .description("Phù thủy nhỏ Harry Potter")
                .weightGrams(300)
                .productType(Product.ProductType.BOOK)
                .category(cat)
                .isActive(true)
                .build();

        Publisher pub = new Publisher();
        pub.setId(2L);
        pub.setName("NXB Trẻ");

        Author author = new Author();
        author.setId(3L);
        author.setName("J.K. Rowling");

        Book book = Book.builder()
                .productId(1L)
                .isbn("978-604-1-12345-6")
                .publisher(pub)
                .authors(Set.of(author))
                .seriesName("Harry Potter")
                .volumeNumber(1)
                .coverType(Book.CoverType.PAPERBACK)
                .pageCount(350)
                .publicationYear(2021)
                .isTextbook(false)
                .language("Tiếng Việt")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(bookRepository.findById(1L)).thenReturn(Optional.of(book));

        // Act
        ProductDetailResponse detail = productService.viewDetail(1L);

        // Assert
        assertThat(detail).isNotNull();
        assertThat(detail.id()).isEqualTo(1L);
        assertThat(detail.title()).isEqualTo("Harry Potter Tập 1");
        assertThat(detail.isbn()).isEqualTo("978-604-1-12345-6");
        assertThat(detail.publisherName()).isEqualTo("NXB Trẻ");
        assertThat(detail.authorNames()).containsExactly("J.K. Rowling");
        assertThat(detail.seriesName()).isEqualTo("Harry Potter");
        assertThat(detail.volumeNumber()).isEqualTo(1);
        assertThat(detail.categoryName()).isEqualTo("Văn Học");
    }

    @Test
    @DisplayName("Should retrieve full product detail for Stationery (UC03)")
    void shouldViewDetailForStationery() {
        // Arrange
        Category cat = new Category();
        cat.setId(8L);
        cat.setName("Dụng Cụ Học Tập");

        Product product = Product.builder()
                .id(2L)
                .barcode("BAR-TL01")
                .title("Bút Bi Thiên Long Gel")
                .price(BigDecimal.valueOf(12000))
                .stockQuantity(50)
                .productType(Product.ProductType.STATIONERY)
                .category(cat)
                .isActive(true)
                .build();

        Brand brand = new Brand();
        brand.setId(4L);
        brand.setName("Thiên Long");

        Stationery stationery = Stationery.builder()
                .productId(2L)
                .brand(brand)
                .material("Nhựa cao cấp")
                .color("Xanh")
                .warrantyMonths(6)
                .build();

        when(productRepository.findById(2L)).thenReturn(Optional.of(product));
        when(stationeryRepository.findById(2L)).thenReturn(Optional.of(stationery));

        // Act
        ProductDetailResponse detail = productService.viewDetail(2L);

        // Assert
        assertThat(detail).isNotNull();
        assertThat(detail.id()).isEqualTo(2L);
        assertThat(detail.brandName()).isEqualTo("Thiên Long");
        assertThat(detail.material()).isEqualTo("Nhựa cao cấp");
        assertThat(detail.color()).isEqualTo("Xanh");
        assertThat(detail.warrantyMonths()).isEqualTo(6);
    }

    @Test
    @DisplayName("Should throw NoSuchElementException when product is not found or inactive (UC03)")
    void shouldThrowWhenProductNotFound() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.viewDetail(999L))
                .isInstanceOf(NoSuchElementException.class);
    }
}
