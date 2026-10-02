package com.ddagtech.aureliabooks.repository;

import com.ddagtech.aureliabooks.entity.Publisher;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublisherRepository extends JpaRepository<Publisher, Long> {
    // TODO: owner adds only queries required by their Sprint 1 use cases.
}
