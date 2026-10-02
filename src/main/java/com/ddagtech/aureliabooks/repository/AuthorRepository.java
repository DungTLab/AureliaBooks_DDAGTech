package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Author;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorRepository extends JpaRepository<Author, Long> {
    // TODO: owner adds only queries required by their Sprint 1 use cases.
}
