package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA Repository for {@link User} entity.
 * Provides optimized database query methods with fetch joins to prevent N+1 queries.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
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
     * Eliminates LazyInitializationException and solves N+1 queries during authentication in 22-table schema.
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
}
