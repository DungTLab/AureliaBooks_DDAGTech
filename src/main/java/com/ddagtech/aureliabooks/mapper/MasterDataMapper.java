package com.ddagtech.aureliabooks.mapper;

import com.ddagtech.aureliabooks.dto.request.AuthorForm;
import com.ddagtech.aureliabooks.dto.request.BrandForm;
import com.ddagtech.aureliabooks.dto.request.CategoryForm;
import com.ddagtech.aureliabooks.dto.request.PublisherForm;
import com.ddagtech.aureliabooks.dto.response.AuthorSummary;
import com.ddagtech.aureliabooks.dto.response.BrandSummary;
import com.ddagtech.aureliabooks.dto.response.CategorySummary;
import com.ddagtech.aureliabooks.dto.response.PublisherSummary;
import com.ddagtech.aureliabooks.entity.Author;
import com.ddagtech.aureliabooks.entity.Brand;
import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.entity.Publisher;
import org.springframework.stereotype.Component;

/**
 * Stateless mapper for catalog master data entities (Category, Author, Publisher, Brand).
 */
@Component
public class MasterDataMapper {

    public CategorySummary toCategorySummary(Category c) {
        if (c == null) {
            return null;
        }
        return new CategorySummary(
                c.getId(),
                c.getName(),
                c.getParent() != null ? c.getParent().getId() : null
        );
    }

    public AuthorSummary toAuthorSummary(Author a) {
        if (a == null) {
            return null;
        }
        return new AuthorSummary(a.getId(), a.getName());
    }

    public PublisherSummary toPublisherSummary(Publisher p) {
        if (p == null) {
            return null;
        }
        return new PublisherSummary(p.getId(), p.getName());
    }

    public BrandSummary toBrandSummary(Brand b) {
        if (b == null) {
            return null;
        }
        return new BrandSummary(b.getId(), b.getName());
    }

    /**
     * Converts AuthorForm request DTO to new Author JPA entity.
     */
    public Author toAuthorEntity(AuthorForm form) {
        if (form == null) {
            return null;
        }
        return Author.builder()
                .name(form.name() != null ? form.name().strip() : null)
                .biography(form.biography() != null && !form.biography().isBlank() ? form.biography().strip() : null)
                .build();
    }

    /**
     * Updates an existing Author entity with AuthorForm request DTO fields.
     */
    public void updateAuthorEntity(Author author, AuthorForm form) {
        if (author == null || form == null) {
            return;
        }
        author.setName(form.name() != null ? form.name().strip() : null);
        author.setBiography(form.biography() != null && !form.biography().isBlank() ? form.biography().strip() : null);
    }

    /**
     * Converts PublisherForm request DTO to new Publisher JPA entity.
     */
    public Publisher toPublisherEntity(com.ddagtech.aureliabooks.dto.request.PublisherForm form) {
        if (form == null) {
            return null;
        }
        return Publisher.builder()
                .name(form.name() != null ? form.name().strip() : null)
                .address(form.address() != null && !form.address().isBlank() ? form.address().strip() : null)
                .email(form.email() != null && !form.email().isBlank() ? form.email().strip() : null)
                .build();
    }

    /**
     * Updates an existing Publisher entity with PublisherForm request DTO fields.
     */
    public void updatePublisherEntity(Publisher publisher, PublisherForm form) {
        if (publisher == null || form == null) {
            return;
        }
        publisher.setName(form.name() != null ? form.name().strip() : null);
        publisher.setAddress(form.address() != null && !form.address().isBlank() ? form.address().strip() : null);
        publisher.setEmail(form.email() != null && !form.email().isBlank() ? form.email().strip() : null);
    }

    /**
     * Converts BrandForm request DTO to new Brand JPA entity.
     */
    public Brand toBrandEntity(BrandForm form) {
        if (form == null) {
            return null;
        }
        return Brand.builder()
                .name(form.name() != null ? form.name().strip() : null)
                .originCountry(form.originCountry() != null && !form.originCountry().isBlank() ? form.originCountry().strip() : null)
                .build();
    }

    /**
     * Updates an existing Brand entity with BrandForm request DTO fields.
     */
    public void updateBrandEntity(Brand brand, BrandForm form) {
        if (brand == null || form == null) {
            return;
        }
        brand.setName(form.name() != null ? form.name().strip() : null);
        brand.setOriginCountry(form.originCountry() != null && !form.originCountry().isBlank() ? form.originCountry().strip() : null);
    }

    /**
     * Converts CategoryForm request DTO and resolved parent entity to new Category JPA entity.
     * Unchecked active checkbox resolves to Boolean.FALSE via form binding or explicit false fallback.
     */
    public Category toCategoryEntity(CategoryForm form, Category parent) {
        if (form == null) {
            return null;
        }
        return Category.builder()
                .name(form.name() != null ? form.name().strip() : null)
                .parent(parent)
                .description(form.description() != null && !form.description().isBlank() ? form.description().strip() : null)
                .isActive(Boolean.TRUE.equals(form.active()))
                .build();
    }

    /**
     * Updates an existing Category entity with CategoryForm request DTO fields and resolved parent.
     * Explicitly syncs isActive to false when active checkbox is unchecked (null or false).
     */
    public void updateCategoryEntity(Category category, CategoryForm form, Category parent) {
        if (category == null || form == null) {
            return;
        }
        category.setName(form.name() != null ? form.name().strip() : null);
        category.setParent(parent);
        category.setDescription(form.description() != null && !form.description().isBlank() ? form.description().strip() : null);
        category.setIsActive(Boolean.TRUE.equals(form.active()));
    }
}
