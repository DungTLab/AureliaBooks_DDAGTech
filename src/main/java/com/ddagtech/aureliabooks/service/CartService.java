package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.CartQuantityRequest;
import com.ddagtech.aureliabooks.dto.response.CartView;

public interface CartService {
    void addRecommendations(Long userId, java.util.List<Long> productIds);

    CartView viewCart(Long userId);

    long countItems(Long userId);

    void addItem(Long userId, Long productId, CartQuantityRequest request);
    void removeItem(Long userId,Long itemId);
    void updateQuantity(Long userId,Long itemId, CartQuantityRequest request);
}
