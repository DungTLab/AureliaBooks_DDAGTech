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
public class StockMovementReportItemDto {

    private static final Locale VI_VN = Locale.of("vi", "VN");

    private Long productId;
    private String barcode;
    private String title;
    private String categoryName;
    private Product.ProductType productType;
    private Boolean isActive;

    @Builder.Default
    private BigDecimal originalCost = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    @Builder.Default
    private int openingStock = 0;

    @Builder.Default
    private int importQty = 0;

    @Builder.Default
    private int orderDeductQty = 0;

    @Builder.Default
    private int cancelRestoreQty = 0;

    @Builder.Default
    private int returnRestoreQty = 0;

    @Builder.Default
    private int closingStock = 0;

    @Builder.Default
    private BigDecimal openingCostValue = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal closingCostValue = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal closingRetailValue = BigDecimal.ZERO;

    public String getProductTypeLabel() {
        if (productType == null) {
            return "Khác";
        }
        return productType == Product.ProductType.BOOK ? "Sách" : "Văn phòng phẩm";
    }

    public String getActiveStatusLabel() {
        return Boolean.TRUE.equals(isActive) ? "Đang kinh doanh" : "Ngừng kinh doanh";
    }

    public String getFormattedOriginalCost() {
        return formatCurrency(originalCost);
    }

    public String getFormattedPrice() {
        return formatCurrency(price);
    }

    public String getFormattedOpeningCostValue() {
        return formatCurrency(openingCostValue);
    }

    public String getFormattedClosingCostValue() {
        return formatCurrency(closingCostValue);
    }

    public String getFormattedClosingRetailValue() {
        return formatCurrency(closingRetailValue);
    }

    private static String formatCurrency(BigDecimal amount) {
        if (amount == null) {
            return "0 ₫";
        }
        NumberFormat nf = NumberFormat.getInstance(VI_VN);
        return nf.format(amount) + " ₫";
    }
}
