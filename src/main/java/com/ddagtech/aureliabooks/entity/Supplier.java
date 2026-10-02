package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** UC30. Owner: Nguyễn Trần Đức Anh. Sprint 1 scaffold; business implementation pending. */
@Entity
@Table(name = "suppliers")
@Getter
@Setter
public class Supplier {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 150, nullable = false)
    private String name;
    @Column(name = "contact_name", length = 100)
    private String contactName;
    @Column(name = "phone", length = 15, nullable = false)
    private String phone;
    @Column(name = "email", length = 100)
    private String email;
    @Column(name = "address", length = 255)
    private String address;
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
