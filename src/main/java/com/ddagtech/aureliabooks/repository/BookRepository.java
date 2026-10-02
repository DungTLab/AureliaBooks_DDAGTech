package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookRepository extends JpaRepository<Book, Long> {
    // TODO: owner adds only queries required by their Sprint 1 use cases.
}
