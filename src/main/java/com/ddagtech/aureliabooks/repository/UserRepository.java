package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for {@link User} entity.
 * Provides optimized database query methods with fetch joins to prevent N+1 queries.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.id = :id")
    Optional<User> findOwnerForUpdate(@Param("id") Long id);
    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.authProvider = :provider AND u.providerId = :subject")
    Optional<User> findByGoogleIdentity(@Param("provider") User.AuthProvider provider, @Param("subject") String subject);

    /**
     * Finds a user by email address.
     *
     * @param email user email address
     * @return Optional containing the user if found
     */
    Optional<User> findByEmail(String email);

    /**
     * Finds a user by mobile phone number.
     *
     * @param phone user phone number
     * @return Optional containing the user if found
     */
    Optional<User> findByPhone(String phone);

    /**
     * Finds a user by either email or phone number with role eagerly fetched.
     * Loads the single assigned role with the account in the 21-table schema.
     *
     * @param identifier user email or phone number
     * @return Optional containing the user with populated role if found
     */
    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.email = :identifier OR u.phone = :identifier")
    Optional<User> findByIdentifierWithRoles(@Param("identifier") String identifier);

    /**
     * Checks if a user exists with the given email address.
     *
     * @param email user email address
     * @return true if exists, false otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Checks if a user exists with the given mobile phone number.
     *
     * @param phone user phone number
     * @return true if exists, false otherwise
     */
    boolean existsByPhone(String phone);

    /**
     * Counts the total number of active administrator accounts in the system.
     * Used to prevent revoking the privilege or deactivating the last remaining administrator (BR-08-01).
     *
     * @return number of active admin users
     */
    @Query("SELECT COUNT(u) FROM User u JOIN u.role r WHERE r.roleName = 'ROLE_ADMIN' AND u.isActive = true")
    long countActiveAdmins();

    /**
     * Retrieves all currently active administrator accounts with a pessimistic write lock (FOR UPDATE).
     * In MySQL InnoDB with REPEATABLE READ isolation, executing a Locking Read bypasses the transaction's
     * consistent read snapshot (Read View) and always reads the latest committed database state,
     * strictly preventing concurrent transactions from deactivating or revoking the last administrator (UC28-R05).
     *
     * @return list of active admin user entities with loaded roles
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u JOIN FETCH u.role r WHERE r.roleName = 'ROLE_ADMIN' AND u.isActive = true")
    List<User> findActiveAdminsForUpdate();

    /**
     * Retrieves paginated internal staff users filtered optionally by role name.
     * Overload for backward compatibility.
     *
     * @param roleName optional role name filter (e.g. ROLE_ADMIN, ROLE_MANAGER, ROLE_SALE_STAFF)
     * @param pageable pagination and sorting parameters
     * @return page of matching internal user entities with loaded roles
     */
    default Page<User> findInternalStaff(String roleName, Pageable pageable) {
        return findInternalStaff(roleName, null, null, pageable);
    }

    /**
     * Retrieves paginated internal staff users filtered by optional role name, active status, and search keyword.
     * Utilizes JOIN FETCH on the role relationship to strictly eliminate N+1 queries.
     *
     * @param roleName optional role name filter (e.g. ROLE_ADMIN, ROLE_MANAGER, ROLE_SALE_STAFF)
     * @param active optional account active state filter
     * @param keyword optional search term matching fullName, email, or phone
     * @param pageable pagination and sorting parameters
     * @return page of matching internal user entities with loaded roles
     */
    @Query(value = "SELECT u FROM User u JOIN FETCH u.role r WHERE " +
           "(:roleName IS NULL OR r.roleName = :roleName) " +
           "AND (:active IS NULL OR u.isActive = :active) " +
           "AND (:keyword IS NULL OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR u.phone LIKE CONCAT('%', :keyword, '%'))",
           countQuery = "SELECT COUNT(u) FROM User u JOIN u.role r WHERE " +
           "(:roleName IS NULL OR r.roleName = :roleName) " +
           "AND (:active IS NULL OR u.isActive = :active) " +
           "AND (:keyword IS NULL OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR u.phone LIKE CONCAT('%', :keyword, '%'))")
    Page<User> findInternalStaff(
            @Param("roleName") String roleName,
            @Param("active") Boolean active,
            @Param("keyword") String keyword,
            Pageable pageable
    );
}

