package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.constant.ErrorCode;
import com.ddagtech.aureliabooks.dto.request.*;
import com.ddagtech.aureliabooks.entity.*;
import com.ddagtech.aureliabooks.exception.AppException;
import com.ddagtech.aureliabooks.mapper.ProductMapper;
import com.ddagtech.aureliabooks.repository.*;
import com.ddagtech.aureliabooks.service.impl.AdminProductServiceImpl;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ProductImageSaveGuardTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final BookRepository books = mock(BookRepository.class);
    private final StationeryRepository stationery = mock(StationeryRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final PublisherRepository publishers = mock(PublisherRepository.class);
    private final AuthorRepository authors = mock(AuthorRepository.class);
    private final BrandRepository brands = mock(BrandRepository.class);
    private final ProductImageStorage storage = mock(ProductImageStorage.class);
    private final AdminProductServiceImpl service = new AdminProductServiceImpl(products, books, stationery,
            categories, publishers, authors, brands, new ProductMapper(), new ProductImageReferencePolicy(
                    new StaticListableBeanFactory(Map.of("storage", storage)).getBeanProvider(ProductImageStorage.class)));
    private static final String ID = "550e8400-e29b-41d4-a716-446655440000";
    private final BookForm book = new BookForm(null, "893500000001", "Book", 1L, BigDecimal.TEN,
            BigDecimal.ONE, 200, "/products/images/" + ID, null, null, "080442957X", 1L,
            Set.of(1L), null, null, false, 2024, null, 100, "vi", Book.CoverType.PAPERBACK);
    private final StationeryForm pen = new StationeryForm(null, "893500000001", "Pen", 1L,
            BigDecimal.TEN, BigDecimal.ONE, 10, "/products/images/" + ID, null, null, 1L, null, null, 0);

    private void missing() { when(storage.read(ID)).thenThrow(new AppException(ErrorCode.RESOURCE_NOT_FOUND)); }
    private Product old() { return Product.builder().mainImageUrl("https://example.com/old.png").stockQuantity(7).build(); }
    @Test void createBookRejectsMissingObjectBeforeWrites() {
        missing();
        assertThatThrownBy(() -> service.createBook(book)).isInstanceOf(AppException.class);
        verifyNoInteractions(products, books, stationery, categories, publishers, authors, brands);
    }
    @Test void createStationeryRejectsMissingObjectBeforeWrites() {
        missing();
        assertThatThrownBy(() -> service.createStationery(pen)).isInstanceOf(AppException.class);
        verifyNoInteractions(products, books, stationery, categories, publishers, authors, brands);
    }
    @Test void failedBookReplacementPreservesOldUrlAndStock() {
        missing();
        Product old = old();
        when(books.findByIdWithDetails(1L)).thenReturn(Optional.of(Book.builder().product(old).build()));
        assertThatThrownBy(() -> service.updateBook(1L, book)).isInstanceOf(AppException.class);
        assertThat(old.getMainImageUrl()).isEqualTo("https://example.com/old.png");
        assertThat(old.getStockQuantity()).isEqualTo(7);
        verify(products, never()).save(any());
    }
    @Test void failedStationeryReplacementPreservesOldUrlAndStock() {
        missing();
        Product old = old();
        when(stationery.findByIdWithDetails(1L)).thenReturn(Optional.of(Stationery.builder().product(old).build()));
        assertThatThrownBy(() -> service.updateStationery(1L, pen)).isInstanceOf(AppException.class);
        assertThat(old.getMainImageUrl()).isEqualTo("https://example.com/old.png");
        assertThat(old.getStockQuantity()).isEqualTo(7);
        verify(products, never()).save(any());
    }
}
