package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Publisher;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublisherRepository extends JpaRepository<Publisher, Long> {
    /**
     * Checks if a publisher with the given name exists.
     *
     * @param name publisher name
     * @return true if publisher exists
     */
    boolean existsByNameIgnoreCase(String name);

    /**
     * Checks if another publisher with the given name exists excluding current ID.
     *
     * @param name publisher name
     * @param id   publisher ID to exclude
     * @return true if another publisher exists
     */
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    /**
     * Returns all publishers ordered by name ascending for dropdown selection.
     *
     * @return list of publishers
     */
    java.util.List<Publisher> findAllByOrderByNameAsc();

    /**
     * Verifies if any book is currently linked to the specified publisher.
     *
     * @param publisherId publisher ID
     * @return true if at least one book references this publisher
     */
    @org.springframework.data.jpa.repository.Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM Book b WHERE b.publisher.id = :publisherId")
    boolean hasBooks(@org.springframework.data.repository.query.Param("publisherId") Long publisherId);
}
