package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import org.hibernate.annotations.CreationTimestamp;

/** UC09. Owner: Lê Tiến Dũng. Sprint 1 scaffold; business implementation pending. */
@Entity
@Table(name = "shipping_addresses")
@Getter
@Setter
public class ShippingAddress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "recipient_name", length = 100, nullable = false)
    private String recipientName;
    @Column(name = "phone", length = 15, nullable = false)
    private String phone;
    @Enumerated(EnumType.STRING)
    @Column(name = "economic_region", nullable = false)
    private EconomicRegion economicRegion;
    @Column(name = "province", length = 100, nullable = false)
    private String province;
    @Column(name = "district", length = 100, nullable = false)
    private String district;
    @Column(name = "ward", length = 100, nullable = false)
    private String ward;
    @Column(name = "detailed_address", length = 255, nullable = false)
    private String detailedAddress;
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    public enum EconomicRegion { NORTHERN, CENTRAL_HIGHLANDS, SOUTHERN_MEKONG }
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
