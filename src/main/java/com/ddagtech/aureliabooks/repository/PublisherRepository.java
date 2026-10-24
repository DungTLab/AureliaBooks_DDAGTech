package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Publisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
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
    List<Publisher> findAllByOrderByNameAsc();

    /**
     * Returns only active publishers ordered by name ascending.
     *
     * @return list of active publishers
     */
    List<Publisher> findAllByIsActiveTrueOrderByNameAsc();

    Page<Publisher> findAllByOrderByNameAsc(Pageable pageable);

    Page<Publisher> findByNameContainingIgnoreCaseOrderByNameAsc(String keyword, Pageable pageable);

    /**
     * Verifies if any book is currently linked to the specified publisher.
     *
     * @param publisherId publisher ID
     * @return true if at least one book references this publisher
     */
    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM Book b WHERE b.publisher.id = :publisherId")
    boolean hasBooks(@Param("publisherId") Long publisherId);

    @Query("SELECT COUNT(b) FROM Book b WHERE b.publisher.id = :publisherId")
    long countBooksByPublisherId(@Param("publisherId") Long publisherId);
}
