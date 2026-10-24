package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Author;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorRepository extends JpaRepository<Author, Long> {
    /**
     * Checks if another author with the specified name exists (case-insensitive).
     *
     * @param name author name to check
     * @return true if an author exists with the given name
     */
    boolean existsByNameIgnoreCase(String name);

    /**
     * Checks if another author with the specified name exists excluding the given author ID.
     *
     * @param name author name to check
     * @param id   author ID to exclude
     * @return true if another author exists with the given name
     */
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    /**
     * Searches authors by name with pagination.
     *
     * @param name     keyword in author name
     * @param pageable pagination parameters
     * @return page of matched authors
     */
    org.springframework.data.domain.Page<Author> findByNameContainingIgnoreCaseOrderByNameAsc(String name, org.springframework.data.domain.Pageable pageable);

    /**
     * Returns all authors ordered by name ascending with pagination.
     *
     * @param pageable pagination parameters
     * @return page of authors
     */
    org.springframework.data.domain.Page<Author> findAllByOrderByNameAsc(org.springframework.data.domain.Pageable pageable);

    /**
     * Returns all authors ordered by name ascending for selection dropdowns.
     *
     * @return list of all authors
     */
    java.util.List<Author> findAllByOrderByNameAsc();

    /**
     * Returns all active authors ordered by name ascending for selection dropdowns.
     *
     * @return list of active authors
     */
    java.util.List<Author> findAllByIsActiveTrueOrderByNameAsc();

    /**
     * Verifies if any book is currently linked to the specified author.
     *
     * @param authorId author ID to verify
     * @return true if at least one book references this author
     */
    @org.springframework.data.jpa.repository.Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM Book b JOIN b.authors a WHERE a.id = :authorId")
    boolean hasBooks(@org.springframework.data.repository.query.Param("authorId") Long authorId);

    /**
     * Counts the total number of books associated with the specified author.
     *
     * @param authorId author ID
     * @return count of associated books
     */
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(b) FROM Book b JOIN b.authors a WHERE a.id = :authorId")
    long countBooksByAuthorId(@org.springframework.data.repository.query.Param("authorId") Long authorId);
}
