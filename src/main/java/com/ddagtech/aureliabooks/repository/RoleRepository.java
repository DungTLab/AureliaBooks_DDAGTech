package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByRoleName(String roleName);

    /**
     * Acquires a pessimistic write lock (SELECT ... FOR UPDATE) on the specified role record.
     * Serializes administrative mutations across the entire database transaction lifecycle until commit.
     *
     * @param roleName role name to lock (e.g. 'ROLE_ADMIN')
     * @return locked role entity
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Role r WHERE r.roleName = :roleName")
    Optional<Role> findByRoleNameForUpdate(@Param("roleName") String roleName);
}
