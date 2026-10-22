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
public class TopProductSaleDto {

    private static final Locale VI_VN = Locale.of("vi", "VN");

    private Long productId;
    private String barcode;
    private String title;
    private Product.ProductType productType;
    private String mainImageUrl;

    @Builder.Default
    private long totalQuantitySold = 0L;

    @Builder.Default
    private BigDecimal totalRevenue = BigDecimal.ZERO;

    public String getFormattedTotalRevenue() {
        if (totalRevenue == null) {
            return "0 ₫";
        }
        NumberFormat nf = NumberFormat.getInstance(VI_VN);
        return nf.format(totalRevenue) + " ₫";
    }

    public String getProductTypeLabel() {
        if (productType == null) {
            return "Khác";
        }
        return productType == Product.ProductType.BOOK ? "Sách" : "Văn phòng phẩm";
    }
}
