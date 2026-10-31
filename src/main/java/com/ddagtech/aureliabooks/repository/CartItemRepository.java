package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.CartItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    @EntityGraph(attributePaths = "product")
    @Query("select item from CartItem item where item.cart.id = :cartId order by item.createdAt, item.id")
    List<CartItem> findAllByCart_Id(@Param("cartId") Long cartId);

    @Query("select coalesce(sum(item.quantity), 0) from CartItem item where item.cart.user.id = :userId")
    Long sumQuantityByUserId(@Param("userId") Long userId);

    Optional<CartItem> findByCart_IdAndProduct_Id(Long cartId, Long productId);

    long countByCart_Id(Long cartId);

    @EntityGraph(attributePaths = {"cart", "product"})
    Optional<CartItem> findByIdAndCart_User_Id(Long itemId, Long userId);
}
