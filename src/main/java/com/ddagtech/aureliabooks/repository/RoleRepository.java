package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA Repository for {@link Role} entity.
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    /**
     * Finds a role by its unique canonical role name (e.g., 'ROLE_ADMIN', 'ROLE_CUSTOMER').
     *
     * @param roleName canonical role name
     * @return Optional containing the Role if found
     */
    Optional<Role> findByRoleName(String roleName);
}
