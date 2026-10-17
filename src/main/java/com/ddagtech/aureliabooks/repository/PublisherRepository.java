package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Publisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PublisherRepository extends JpaRepository<Publisher, Long> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    List<Publisher> findAllByOrderByNameAsc();

    Page<Publisher> findAllByOrderByNameAsc(Pageable pageable);

    Page<Publisher> findByNameContainingIgnoreCaseOrderByNameAsc(String keyword, Pageable pageable);

    @Query("SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END FROM Book b WHERE b.publisher.id = :publisherId")
    boolean hasBooks(@Param("publisherId") Long publisherId);

    @Query("SELECT COUNT(b) FROM Book b WHERE b.publisher.id = :publisherId")
    long countBooksByPublisherId(@Param("publisherId") Long publisherId);
}
