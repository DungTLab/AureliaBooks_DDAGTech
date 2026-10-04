package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.CartQuantityRequest;
import com.ddagtech.aureliabooks.dto.response.CartView;

public interface CartService {
    CartView viewCart(Long userId);

    void addItem(Long userId, Long productId, CartQuantityRequest request);
    void removeItem(Long userId,Long itemId);
}
