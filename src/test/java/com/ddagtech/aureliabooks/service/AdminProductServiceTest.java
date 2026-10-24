package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.BookForm;
import com.ddagtech.aureliabooks.dto.request.StationeryForm;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.service.impl.AdminProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit test suite for AdminProductService (UC15: Books, UC16: Stationery, UC17: Catalog Governance).
 * Enforces business invariants: Table-Per-Type inheritance, read-only stock quantities, and unique constraints.
 */
@ExtendWith(MockitoExtension.class)
class AdminProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private StationeryRepository stationeryRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private PublisherRepository publisherRepository;

    @Mock
    private AuthorRepository authorRepository;

    @Mock
    private BrandRepository brandRepository;

    @org.mockito.Spy
    private com.ddagtech.aureliabooks.mapper.ProductMapper productMapper = new com.ddagtech.aureliabooks.mapper.ProductMapper();

    @InjectMocks
    private AdminProductServiceImpl adminProductService;

    private Category bookCategory;
    private Category stationeryCategory;
    private Publisher publisher;
    private Author author;
    private Brand brand;
    private Product sampleBookProduct;
    private Book sampleBook;

    @BeforeEach
    void setUp() {
        bookCategory = Category.builder().id(1L).name("Văn Học").isActive(true).build();
        stationeryCategory = Category.builder().id(2L).name("Bút Ký").isActive(true).build();
        publisher = Publisher.builder().id(1L).name("NXB Trẻ").build();
        author = Author.builder().id(1L).name("Nguyễn Nhật Ánh").build();
        brand = Brand.builder().id(1L).name("Thiên Long").build();

        sampleBookProduct = Product.builder()
                .id(100L)
                .barcode("893500000001")
                .title("Mắt Biếc")
                .category(bookCategory)
                .productType(Product.ProductType.BOOK)
                .price(BigDecimal.valueOf(110000))
                .originalCost(BigDecimal.valueOf(70000))
                .stockQuantity(50)
                .isActive(true)
                .build();

        sampleBook = Book.builder()
                .productId(100L)
                .product(sampleBookProduct)
                .isbn("978-604-1-00001-1")
                .publisher(publisher)
                .authors(Set.of(author))
                .coverType(Book.CoverType.PAPERBACK)
                .build();
    }

    @Nested
    @DisplayName("Product Listing & Filtering Tests (UC15/16)")
    class ListProductsTests {

        @Test
        @DisplayName("listProducts without filter calls productRepository.findAll")
        void listProducts_NoFilter_CallsFindAll() {
            Pageable pageable = PageRequest.of(0, 10);
            when(productRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(sampleBookProduct)));

            Page<Product> result = adminProductService.listProducts(null, null, pageable);

            assertThat(result).hasSize(1);
            assertThat(result.getContent().get(0).getTitle()).isEqualTo("Mắt Biếc");
            verify(productRepository).findAll(pageable);
            verifyNoMoreInteractions(productRepository);
        }

        @Test
        @DisplayName("listProducts with productType only calls productRepository.findByProductType")
        void listProducts_TypeOnly_CallsFindByProductType() {
            Pageable pageable = PageRequest.of(0, 10);
            when(productRepository.findByProductType(Product.ProductType.BOOK, pageable))
                    .thenReturn(new PageImpl<>(List.of(sampleBookProduct)));

            Page<Product> result = adminProductService.listProducts(Product.ProductType.BOOK, "", pageable);

            assertThat(result).hasSize(1);
            verify(productRepository).findByProductType(Product.ProductType.BOOK, pageable);
        }

        @Test
        @DisplayName("listProducts with keyword only calls productRepository.findByTitleContainingIgnoreCaseOrBarcodeContainingIgnoreCase")
        void listProducts_KeywordOnly_CallsFindByTitleOrBarcode() {
            Pageable pageable = PageRequest.of(0, 10);
            when(productRepository.findByTitleContainingIgnoreCaseOrBarcodeContainingIgnoreCase("Biếc", "Biếc", pageable))
                    .thenReturn(new PageImpl<>(List.of(sampleBookProduct)));

            Page<Product> result = adminProductService.listProducts(null, " Biếc ", pageable);

            assertThat(result).hasSize(1);
            verify(productRepository).findByTitleContainingIgnoreCaseOrBarcodeContainingIgnoreCase("Biếc", "Biếc", pageable);
        }

        @Test
        @DisplayName("listProducts with type and keyword calls productRepository.findByProductTypeAndKeyword")
        void listProducts_TypeAndKeyword_CallsFindByProductTypeAndKeyword() {
            Pageable pageable = PageRequest.of(0, 10);
            when(productRepository.findByProductTypeAndKeyword(Product.ProductType.BOOK, "Biếc", "Biếc", pageable))
                    .thenReturn(new PageImpl<>(List.of(sampleBookProduct)));

            Page<Product> result = adminProductService.listProducts(Product.ProductType.BOOK, "Biếc", pageable);

            assertThat(result).hasSize(1);
            verify(productRepository).findByProductTypeAndKeyword(Product.ProductType.BOOK, "Biếc", "Biếc", pageable);
        }
    }

    @Nested
    @DisplayName("Book Creation & Update Tests (UC15)")
    class BookManagementTests {

        @Test
        @DisplayName("createBook strictly initializes stockQuantity to 0 and persists base product + book details")
        void createBook_Success_InitializesStockToZero() {
            BookForm form = new BookForm(
                    null,
                    "893500000002",
                    "Tôi Thấy Hoa Vàng Trên Cỏ Xanh",
                    1L,
                    BigDecimal.valueOf(125000),
                    BigDecimal.valueOf(80000),
                    250,
                    "/images/hoa-vang.jpg",
                    "van-hoc,nguyen-nhat-anh",
                    "Tác phẩm nổi tiếng",
                    "978-604-1-00002-2",
                    1L,
                    Set.of(1L),
                    null,
                    null,
                    false,
                    2023,
                    "Tái bản lần 5",
                    378,
                    "Tiếng Việt",
                    Book.CoverType.PAPERBACK
            );

            when(productRepository.existsByBarcodeIgnoreCase("893500000002")).thenReturn(false);
            when(bookRepository.existsByIsbnIgnoreCase("978-604-1-00002-2")).thenReturn(false);
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(bookCategory));
            when(publisherRepository.findById(1L)).thenReturn(Optional.of(publisher));
            when(authorRepository.findAllById(Set.of(1L))).thenReturn(List.of(author));

            Product createdProduct = Product.builder().id(200L).barcode("893500000002").title(form.title()).build();
            when(productRepository.save(any(Product.class))).thenReturn(createdProduct);

            Long resultId = adminProductService.createBook(form);

            assertThat(resultId).isEqualTo(200L);

            ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
            verify(productRepository).save(productCaptor.capture());
            Product capturedProduct = productCaptor.getValue();

            // Invariant: Stock must ALWAYS start at 0 (cannot be input from creation form)
            assertThat(capturedProduct.getStockQuantity()).isEqualTo(0);
            assertThat(capturedProduct.getBarcode()).isEqualTo("893500000002");
            assertThat(capturedProduct.getProductType()).isEqualTo(Product.ProductType.BOOK);
            assertThat(capturedProduct.getIsActive()).isTrue();

            ArgumentCaptor<Book> bookCaptor = ArgumentCaptor.forClass(Book.class);
            verify(bookRepository).save(bookCaptor.capture());
            Book capturedBook = bookCaptor.getValue();
            assertThat(capturedBook.getIsbn()).isEqualTo("978-604-1-00002-2");
            assertThat(capturedBook.getPublisher()).isEqualTo(publisher);
        }

        @Test
        @DisplayName("createBook throws AppException when barcode already exists")
        void createBook_DuplicateBarcode_ThrowsAppException() {
            BookForm form = new BookForm(
                    null, "893500000001", "Duplicate Barcode Book", 1L,
                    BigDecimal.valueOf(100000), BigDecimal.valueOf(60000), 200,
                    null, null, null, "978-604-1-99999-9", 1L, Set.of(),
                    null, null, false, 2023, null, null, null, null
            );

            when(productRepository.existsByBarcodeIgnoreCase("893500000001")).thenReturn(true);

            assertThatThrownBy(() -> adminProductService.createBook(form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);

            verify(productRepository, never()).save(any());
            verify(bookRepository, never()).save(any());
        }

        @Test
        @DisplayName("createBook throws AppException when ISBN already exists")
        void createBook_DuplicateIsbn_ThrowsAppException() {
            BookForm form = new BookForm(
                    null, "893500000003", "Duplicate ISBN Book", 1L,
                    BigDecimal.valueOf(100000), BigDecimal.valueOf(60000), 200,
                    null, null, null, "978-604-1-00001-1", 1L, Set.of(),
                    null, null, false, 2023, null, null, null, null
            );

            when(productRepository.existsByBarcodeIgnoreCase("893500000003")).thenReturn(false);
            when(bookRepository.existsByIsbnIgnoreCase("978-604-1-00001-1")).thenReturn(true);

            assertThatThrownBy(() -> adminProductService.createBook(form))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_DATA);
        }

        @Test
        @DisplayName("updateBook preserves original stockQuantity and updates details")
        void updateBook_Success_PreservesStockQuantity() {
            BookForm updateForm = new BookForm(
                    100L,
                    "893500000001",
                    "Mắt Biếc (Tái Bản Mới)",
                    1L,
                    BigDecimal.valueOf(120000),
                    BigDecimal.valueOf(75000),
                    220,
                    "/images/mat-biec-new.jpg",
                    "classic",
                    "Mô tả cập nhật",
                    "978-604-1-00001-1",
                    1L,
                    Set.of(1L),
                    "Series Nguyễn Nhật Ánh",
                    1,
                    false,
                    2024,
                    "Tái bản 2024",
                    300,
                    "Tiếng Việt",
                    Book.CoverType.HARDCOVER
            );

            when(bookRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(sampleBook));
            when(productRepository.existsByBarcodeIgnoreCaseAndIdNot("893500000001", 100L)).thenReturn(false);
            when(bookRepository.existsByIsbnIgnoreCaseAndProductIdNot("978-604-1-00001-1", 100L)).thenReturn(false);
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(bookCategory));
            when(publisherRepository.findById(1L)).thenReturn(Optional.of(publisher));
            when(authorRepository.findAllById(Set.of(1L))).thenReturn(List.of(author));

            adminProductService.updateBook(100L, updateForm);

            // Invariant: Existing stock quantity (50) MUST NEVER be overwritten or zeroed by an update form
            assertThat(sampleBookProduct.getStockQuantity()).isEqualTo(50);
            assertThat(sampleBookProduct.getTitle()).isEqualTo("Mắt Biếc (Tái Bản Mới)");
            assertThat(sampleBookProduct.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(120000));
            assertThat(sampleBook.getCoverType()).isEqualTo(Book.CoverType.HARDCOVER);

            verify(productRepository).save(sampleBookProduct);
            verify(bookRepository).save(sampleBook);
        }
    }

    @Nested
    @DisplayName("Stationery Creation & Update Tests (UC16)")
    class StationeryManagementTests {

        @Test
        @DisplayName("createStationery strictly initializes stockQuantity to 0 and links brand")
        void createStationery_Success_InitializesStockToZero() {
            StationeryForm form = new StationeryForm(
                    null,
                    "893600000001",
                    "Bút Bi Thiên Long TL-027",
                    2L,
                    BigDecimal.valueOf(5000),
                    BigDecimal.valueOf(2500),
                    15,
                    "/images/tl-027.jpg",
                    "stationery,pen",
                    "Bút bi mực xanh",
                    1L,
                    "Nhựa cao cấp",
                    "Xanh",
                    6
            );

            when(productRepository.existsByBarcodeIgnoreCase("893600000001")).thenReturn(false);
            when(categoryRepository.findById(2L)).thenReturn(Optional.of(stationeryCategory));
            when(brandRepository.findById(1L)).thenReturn(Optional.of(brand));

            Product createdProduct = Product.builder().id(300L).barcode("893600000001").build();
            when(productRepository.save(any(Product.class))).thenReturn(createdProduct);

            Long resultId = adminProductService.createStationery(form);

            assertThat(resultId).isEqualTo(300L);

            ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
            verify(productRepository).save(productCaptor.capture());
            assertThat(productCaptor.getValue().getStockQuantity()).isEqualTo(0);
            assertThat(productCaptor.getValue().getProductType()).isEqualTo(Product.ProductType.STATIONERY);

            ArgumentCaptor<Stationery> stationeryCaptor = ArgumentCaptor.forClass(Stationery.class);
            verify(stationeryRepository).save(stationeryCaptor.capture());
            assertThat(stationeryCaptor.getValue().getBrand()).isEqualTo(brand);
            assertThat(stationeryCaptor.getValue().getColor()).isEqualTo("Xanh");
        }
    }

    @Nested
    @DisplayName("Catalog Governance Tests (UC17)")
    class CatalogGovernanceTests {

        @Test
        @DisplayName("deactivate sets isActive to false")
        void deactivate_Success_SetsIsActiveToFalse() {
            when(productRepository.findById(100L)).thenReturn(Optional.of(sampleBookProduct));

            adminProductService.deactivate(100L);

            assertThat(sampleBookProduct.getIsActive()).isFalse();
            verify(productRepository).save(sampleBookProduct);
        }

        @Test
        @DisplayName("toggleActive flips active boolean state")
        void toggleActive_Success_FlipsActiveState() {
            sampleBookProduct.setIsActive(true);
            when(productRepository.findById(100L)).thenReturn(Optional.of(sampleBookProduct));

            adminProductService.toggleActive(100L);

            assertThat(sampleBookProduct.getIsActive()).isFalse();

            adminProductService.toggleActive(100L);
            assertThat(sampleBookProduct.getIsActive()).isTrue();
        }
    }
}
