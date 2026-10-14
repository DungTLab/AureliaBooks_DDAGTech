package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    @Query("SELECT s FROM Supplier s WHERE " +
           "(:keyword IS NULL OR :keyword = '' OR " +
           " LOWER(s.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " LOWER(s.contactName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " s.phone LIKE CONCAT('%', :keyword, '%') OR " +
           " LOWER(s.email) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:isActive IS NULL OR s.isActive = :isActive)")
    Page<Supplier> searchSuppliers(
            @Param("keyword") String keyword,
            @Param("isActive") Boolean isActive,
            Pageable pageable
    );

    boolean existsByPhone(String phone);

    boolean existsByPhoneAndIdNot(String phone, Long id);

    List<Supplier> findByIsActiveTrueOrderByNameAsc();
}
