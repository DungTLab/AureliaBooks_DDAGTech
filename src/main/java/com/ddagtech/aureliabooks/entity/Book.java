package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "books")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Book {

    @Id
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "isbn", length = 20, nullable = false, unique = true)
    private String isbn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publisher_id", nullable = false)
    private Publisher publisher;

    @Column(name = "series_name", length = 150)
    private String seriesName;

    @Column(name = "volume_number")
    private Integer volumeNumber;

    @Column(name = "is_textbook", nullable = false)
    @Builder.Default
    private Boolean isTextbook = false;

    @Column(name = "publication_year")
    private Integer publicationYear;

    @Column(name = "edition", length = 50)
    private String edition;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "language", length = 50, nullable = false)
    @Builder.Default
    private String language = "Tiếng Việt";

    @Enumerated(EnumType.STRING)
    @Column(name = "cover_type", nullable = false)
    @Builder.Default
    private CoverType coverType = CoverType.PAPERBACK;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "book_authors",
        joinColumns = @JoinColumn(name = "book_id"),
        inverseJoinColumns = @JoinColumn(name = "author_id")
    )
    @Builder.Default
    private Set<Author> authors = new HashSet<>();

    public enum CoverType {
        PAPERBACK, HARDCOVER
    }
}
