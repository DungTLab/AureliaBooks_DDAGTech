package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {
    // TODO: owner adds only queries required by their Sprint 1 use cases.
}
