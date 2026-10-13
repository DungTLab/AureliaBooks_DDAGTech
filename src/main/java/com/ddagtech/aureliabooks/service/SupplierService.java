package com.ddagtech.aureliabooks.service;

import com.ddagtech.aureliabooks.dto.request.SupplierForm;
import com.ddagtech.aureliabooks.dto.response.SupplierSummary;
import com.ddagtech.aureliabooks.entity.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Service contract for Supplier Directory management (UC30).
 * Implemented per Manager CRUD specifications and business rules.
 */
public interface SupplierService {

    /**
     * Creates a new supplier in the directory.
     *
     * @param form supplier form data
     * @return generated ID of the created supplier
     */
    Long create(SupplierForm form);

    /**
     * Updates an existing supplier's details.
     *
     * @param supplierId persistent supplier identifier
     * @param form updated data
     */
    void update(Long supplierId, SupplierForm form);

    /**
     * Deactivates a supplier (sets is_active = false).
     *
     * @param supplierId persistent supplier identifier
     */
    void deactivate(Long supplierId);

    /**
     * Activates a supplier (sets is_active = true).
     *
     * @param supplierId persistent supplier identifier
     */
    void activate(Long supplierId);

    /**
     * Toggles a supplier's active status.
     *
     * @param supplierId persistent supplier identifier
     * @param active desired active state
     */
    void toggleActive(Long supplierId, boolean active);

    /**
     * Hard-deletes a supplier if no goods receipts exist referencing it.
     * Throws {@link com.ddagtech.aureliabooks.exception.AppException} if receipts exist.
     *
     * @param supplierId persistent supplier identifier
     */
    void delete(Long supplierId);

    /**
     * Retrieves a supplier entity by ID.
     *
     * @param supplierId persistent supplier identifier
     * @return found Supplier entity
     */
    Supplier getById(Long supplierId);

    /**
     * Retrieves supplier summary view including receipt count.
     *
     * @param supplierId persistent supplier identifier
     * @return SupplierSummary DTO
     */
    SupplierSummary getSummaryById(Long supplierId);

    /**
     * Retrieves a paginated list of suppliers filtered by keyword and active state.
     *
     * @param keyword optional search term matching name, contact, phone, or email
     * @param active optional active flag filter
     * @param pageable pagination and sorting parameters
     * @return Page of SupplierSummary DTOs
     */
    Page<SupplierSummary> list(String keyword, Boolean active, Pageable pageable);

    /**
     * Retrieves all active suppliers sorted by name.
     *
     * @return List of active SupplierSummary DTOs
     */
    List<SupplierSummary> getActiveSuppliers();

    /**
     * Checks if a supplier is linked to any goods receipts.
     *
     * @param supplierId persistent supplier identifier
     * @return true if one or more receipts exist, false otherwise
     */
    boolean hasReceipts(Long supplierId);
}
