package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.CartItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    @EntityGraph(attributePaths = "product")
    List<CartItem> findAllByCart_Id(Long cartId);
    Optional<CartItem> findByCart_IdAndProduct_Id(Long cartId, Long productId);
    Optional<CartItem> findByIdAndCart_User_Id(Long itemId, Long userId);
}
