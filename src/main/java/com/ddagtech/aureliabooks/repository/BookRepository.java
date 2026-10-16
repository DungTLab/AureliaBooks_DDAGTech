package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long> {
    /**
     * Checks if a book with the given ISBN already exists.
     *
     * @param isbn ISBN-10 or ISBN-13 string
     * @return true if book exists
     */
    boolean existsByIsbnIgnoreCase(String isbn);

    /**
     * Checks if a book with the given ISBN already exists excluding the current product ID.
     *
     * @param isbn      ISBN string
     * @param productId product ID to exclude
     * @return true if another book exists with this ISBN
     */
    boolean existsByIsbnIgnoreCaseAndProductIdNot(String isbn, Long productId);

    /**
     * Finds a book by ISBN.
     *
     * @param isbn ISBN string
     * @return optional book
     */
    Optional<Book> findByIsbn(String isbn);

    /**
     * Loads a book entity with its associated product, publisher, and authors to prevent N+1 queries.
     *
     * @param id book product ID
     * @return optional book with fetched associations
     */
    @Query("SELECT b FROM Book b JOIN FETCH b.product p JOIN FETCH p.category cat JOIN FETCH b.publisher pub LEFT JOIN FETCH b.authors a WHERE b.productId = :id")
    Optional<Book> findByIdWithDetails(@Param("id") Long id);
}
