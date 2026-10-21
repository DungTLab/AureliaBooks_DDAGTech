package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.request.BookForm;
import com.ddagtech.aureliabooks.dto.request.StationeryForm;
import com.ddagtech.aureliabooks.dto.request.SupplierForm;
import com.ddagtech.aureliabooks.dto.response.ReceiptSummary;
import com.ddagtech.aureliabooks.entity.Book;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.GoodsReceipt;
import com.ddagtech.aureliabooks.entity.Product;
import com.ddagtech.aureliabooks.entity.Supplier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubsystemMappersTest {

    private final ProductMapper productMapper = new ProductMapper();
    private final SupplierMapper supplierMapper = new SupplierMapper();
    private final GoodsReceiptMapper goodsReceiptMapper = new GoodsReceiptMapper();

    @Test
    void productMapper_updateFields_neverMutatesStockQuantity() {
        Category category = Category.builder().id(2L).name("Văn Học").build();
        Product product = Product.builder()
                .id(1L)
                .stockQuantity(42)
                .productType(Product.ProductType.BOOK)
                .build();

        BookForm bookForm = new BookForm(
                1L,
                "8935244874389",
                "Nhà Giả Kim",
                2L,
                new BigDecimal("79000.00"),
                new BigDecimal("50000.00"),
                300,
                "/img/nhagiakim.jpg",
                "tieu-thuyet",
                "Mô tả",
                "9786043651591",
                1L,
                Set.of(1L),
                null,
                null,
                false,
                2024,
                "Tái bản 2024",
                228,
                "Tiếng Việt",
                Book.CoverType.PAPERBACK
        );

        productMapper.updateBookProductFields(product, bookForm, category);

        assertThat(product.getTitle()).isEqualTo("Nhà Giả Kim");
        assertThat(product.getStockQuantity()).isEqualTo(42);

        StationeryForm stationeryForm = new StationeryForm(
1L,
                "8935001800018",
                "Bút Bi Thiên Long TL-027",
                6L,
                new BigDecimal("5000.00"),
                new BigDecimal("3000.00"),
                15,
                "/img/tl027.jpg",
                "but-bi",
                "Mô tả bút",
                1L,
                "Nhựa",
                "Xanh",
                6
        );

        productMapper.updateStationeryProductFields(product, stationeryForm, category);

        assertThat(product.getTitle()).isEqualTo("Bút Bi Thiên Long TL-027");
        assertThat(product.getStockQuantity()).isEqualTo(42);
    }

    @Test
    void supplierAndReceiptMappers_mapCorrectly() {
        SupplierForm form = new SupplierForm(" NXB Trẻ ", " Nguyễn Văn A ", "0901234567", "info@nxbtre.com.vn", "TP.HCM", true);
        Supplier entity = supplierMapper.toEntity(form);
        assertThat(entity.getName()).isEqualTo("NXB Trẻ");
        assertThat(entity.getContactName()).isEqualTo("Nguyễn Văn A");

        GoodsReceipt receipt = GoodsReceipt.builder()
                .id(5L)
                .receiptCode("GR-20261017-001")
                .status(GoodsReceipt.Status.RECEIVED)
                .totalAmount(new BigDecimal("1500000.00"))
                .build();
        ReceiptSummary summary = goodsReceiptMapper.toSummary(receipt);
        assertThat(summary.receiptCode()).isEqualTo("GR-20261017-001");
        assertThat(summary.status()).isEqualTo("RECEIVED");
    }
}
