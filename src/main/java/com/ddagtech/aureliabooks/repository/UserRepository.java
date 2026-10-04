package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    // TODO: owner adds only queries required by their Sprint 1 use cases.

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id =:userId")
    Optional<User> findForCartUpdate(@Param("userId") Long userId);
}
