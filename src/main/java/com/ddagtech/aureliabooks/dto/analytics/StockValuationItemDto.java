package com.ddagtech.aureliabooks.dto.analytics;

import com.ddagtech.aureliabooks.entity.Product;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockValuationItemDto {

    private static final Locale VI_VN = Locale.of("vi", "VN");

    private Long productId;
    private String barcode;
    private String title;
    private String categoryName;
    private Product.ProductType productType;

    @Builder.Default
    private Integer stockQuantity = 0;

    @Builder.Default
    private BigDecimal originalCost = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal totalCostValue = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal totalRetailValue = BigDecimal.ZERO;

    private String agingTier;
    private String stockStatus;

    public String getFormattedOriginalCost() {
        return formatCurrency(originalCost);
    }

    public String getFormattedPrice() {
        return formatCurrency(price);
    }

    public String getFormattedTotalCostValue() {
        return formatCurrency(totalCostValue);
    }

    public String getFormattedTotalRetailValue() {
        return formatCurrency(totalRetailValue);
    }

    public String getProductTypeLabel() {
        if (productType == null) {
            return "Khác";
        }
        return productType == Product.ProductType.BOOK ? "Sách" : "Văn phòng phẩm";
    }

    private static String formatCurrency(BigDecimal amount) {
        if (amount == null) {
            return "0 ₫";
        }
        NumberFormat nf = NumberFormat.getInstance(VI_VN);
        return nf.format(amount) + " ₫";
    }
}
