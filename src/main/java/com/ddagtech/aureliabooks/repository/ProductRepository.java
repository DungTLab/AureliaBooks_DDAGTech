package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdWithLock(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id IN :ids ORDER BY p.id ASC")
    List<Product> findAllByIdInWithLock(@Param("ids") Collection<Long> ids);

    /**
     * Retrieves all active products that have a non-null semantic embedding JSON vector
     * for preloading into the in-memory vector store (CRON-02 / FE-6).
     *
     * @return list of active products with embeddings
     */
    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.embedding IS NOT NULL")
    List<Product> findActiveProductsWithEmbedding();
}
