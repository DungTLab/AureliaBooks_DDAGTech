package com.ddagtech.aureliabooks.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test suite for PageableUtils utility methods (FND-02).
 * Verifies pagination boundaries, page size fallback (12 vs 20 items),
 * and sorting parameter resolution.
 */
class PageableUtilsTest {

    @Nested
    @DisplayName("Pagination creation tests")
    class PaginationCreationTests {

        @Test
        @DisplayName("Should use default page size (12) when size parameter is null or zero")
        void shouldDefaultToTwelveItemsPerPageWhenSizeIsNull() {
            // Arrange & Act
            Pageable pageableNull = PageableUtils.create(1, null, "newest");
            Pageable pageableZero = PageableUtils.create(1, 0, "newest");
            Pageable pageableNegative = PageableUtils.create(1, -5, "newest");

            // Assert
            assertThat(pageableNull.getPageSize()).isEqualTo(12);
            assertThat(pageableZero.getPageSize()).isEqualTo(12);
            assertThat(pageableNegative.getPageSize()).isEqualTo(12);
        }

        @Test
        @DisplayName("Should support custom page size (e.g. 20 items per page) when explicitly provided")
        void shouldSupportCustomPageSizeWhenSpecified() {
            // Arrange & Act (Testing UI spec requirement of 20 items/page)
            Pageable pageableTwenty = PageableUtils.create(1, 20, "newest");

            // Assert
            assertThat(pageableTwenty.getPageSize()).isEqualTo(20);
        }

        @Test
        @DisplayName("Should clamp page size to maximum limit of 100")
        void shouldClampPageSizeToMaxLimit() {
            // Arrange & Act
            Pageable pageable = PageableUtils.create(1, 999, "newest");

            // Assert
            assertThat(pageable.getPageSize()).isEqualTo(PageableUtils.MAX_PAGE_SIZE);
        }

        @Test
        @DisplayName("Should convert 1-based page number to 0-based Spring Data page index")
        void shouldConvertOneBasedPageToZeroBasedIndex() {
            // Arrange & Act
            Pageable pageOne = PageableUtils.create(1, 12, "newest");
            Pageable pageFive = PageableUtils.create(5, 12, "newest");
            Pageable pageZero = PageableUtils.create(0, 12, "newest");
            Pageable pageNegative = PageableUtils.create(-1, 12, "newest");

            // Assert
            assertThat(pageOne.getPageNumber()).isEqualTo(0);
            assertThat(pageFive.getPageNumber()).isEqualTo(4);
            assertThat(pageZero.getPageNumber()).isEqualTo(0);
            assertThat(pageNegative.getPageNumber()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Sort resolution tests")
    class SortResolutionTests {

        @Test
        @DisplayName("Should resolve price ascending sort")
        void shouldResolvePriceAscending() {
            // Act
            Sort sort = PageableUtils.resolveSort("price_asc");

            // Assert
            assertThat(sort.getOrderFor("price")).isNotNull();
            assertThat(sort.getOrderFor("price").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        @DisplayName("Should resolve price descending sort")
        void shouldResolvePriceDescending() {
            // Act
            Sort sort = PageableUtils.resolveSort("price_desc");

            // Assert
            assertThat(sort.getOrderFor("price")).isNotNull();
            assertThat(sort.getOrderFor("price").getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("Should resolve name/title ascending sort")
        void shouldResolveTitleAscending() {
            // Act
            Sort sort = PageableUtils.resolveSort("title_asc");

            // Assert
            assertThat(sort.getOrderFor("title")).isNotNull();
            assertThat(sort.getOrderFor("title").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        @DisplayName("Should resolve best sellers sort")
        void shouldResolveBestSellersSort() throws NoSuchFieldException {
            // Act
            Sort sort = PageableUtils.resolveSort("best_sellers");
            Sort hyphenSort = PageableUtils.resolveSort("best-sellers");

            // Assert
            assertThat(com.ddagtech.aureliabooks.entity.Product.class.getDeclaredField("totalSold")).isNotNull();
            assertThat(sort.getOrderFor("totalSold")).isNotNull();
            assertThat(sort.getOrderFor("totalSold").getDirection()).isEqualTo(Sort.Direction.DESC);
            assertThat(sort.getOrderFor("id")).isNotNull();
            assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);

            assertThat(hyphenSort.getOrderFor("totalSold")).isNotNull();
            assertThat(hyphenSort.getOrderFor("totalSold").getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("Should fallback to default sort (createdAt DESC, id DESC) for unknown or null key")
        void shouldFallbackToDefaultSort() {
            // Act
            Sort nullSort = PageableUtils.resolveSort(null);
            Sort blankSort = PageableUtils.resolveSort("   ");
            Sort unknownSort = PageableUtils.resolveSort("invalid_key");

            // Assert
            assertThat(nullSort).isEqualTo(PageableUtils.DEFAULT_SORT);
            assertThat(blankSort).isEqualTo(PageableUtils.DEFAULT_SORT);
            assertThat(unknownSort).isEqualTo(PageableUtils.DEFAULT_SORT);
        }
    }
}
