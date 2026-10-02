package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    // TODO: owner adds only queries required by their Sprint 1 use cases.
}
