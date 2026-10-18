package com.ddagtech.aureliabooks.mapper;

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
}
