package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long> {

    Optional<Book> findByProductId(Long productId);
}
