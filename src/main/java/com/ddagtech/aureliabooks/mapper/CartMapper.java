package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.response.CartItemView;
import com.ddagtech.aureliabooks.dto.response.CartView;
import com.ddagtech.aureliabooks.entity.CartItem;
import com.ddagtech.aureliabooks.entity.Product;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Stateless mapper for CartItem and CartView DTO conversions (UC10).
 */
@Component
public class CartMapper {

    public CartItemView toItemView(CartItem item) {
        if (item == null || item.getProduct() == null) {
            return null;
        }
        Product product = item.getProduct();
        String warning = null;
        if (!Boolean.TRUE.equals(product.getIsActive())) {
            warning = "Sản phẩm hiện không được bán";
        } else if (product.getStockQuantity() == 0) {
            warning = "Sản phẩm đã hết hàng";
        } else if (item.getQuantity() > product.getStockQuantity()) {
            warning = "Không đủ tồn kho. Hiện còn " + product.getStockQuantity() + " sản phẩm";
        }

        BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));

        return new CartItemView(
                item.getId(),
                product.getId(),
                product.getTitle(),
                product.getMainImageUrl(),
                product.getPrice(),
                item.getQuantity(),
                lineTotal,
                product.getStockQuantity(),
                Boolean.TRUE.equals(product.getIsActive()),
                warning
        );
    }

    public CartView toCartView(List<CartItem> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            return new CartView(List.of(), BigDecimal.ZERO, 0);
        }
        List<CartItemView> items = rawItems.stream().map(this::toItemView).toList();
        BigDecimal subTotal = items.stream()
                .map(CartItemView::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalQuantity = items.stream()
                .map(CartItemView::quantity)
                .reduce(0, Integer::sum);

        return new CartView(items, subTotal, totalQuantity);
    }
}
