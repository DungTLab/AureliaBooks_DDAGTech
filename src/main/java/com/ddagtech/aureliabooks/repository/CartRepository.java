package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Cart;
import com.ddagtech.aureliabooks.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByUser_Id(Long userId);

    Long user(User user);
}
