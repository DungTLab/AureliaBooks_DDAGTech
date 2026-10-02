package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "stationeries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Stationery {

    @Id
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    @Column(name = "material", length = 100)
    private String material;

    @Column(name = "color", length = 50)
    private String color;

    @Column(name = "warranty_months", nullable = false)
    @Builder.Default
    private Integer warrantyMonths = 0;
}
