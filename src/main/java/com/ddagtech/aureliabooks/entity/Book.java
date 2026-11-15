package com.ddagtech.aureliabooks.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
