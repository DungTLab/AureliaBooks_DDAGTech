package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long> {

    Optional<Book> findByProductId(Long productId);

    @org.springframework.data.jpa.repository.Query("SELECT b.productId FROM Book b WHERE b.productId IN :productIds AND b.isTextbook = true")
    java.util.Set<Long> findTextbookProductIdsIn(@org.springframework.data.repository.query.Param("productIds") java.util.Collection<Long> productIds);

    @org.springframework.data.jpa.repository.Query("SELECT b.productId FROM Book b WHERE b.isTextbook = true")
    java.util.Set<Long> findProductIdsByIsTextbookTrue();
}
