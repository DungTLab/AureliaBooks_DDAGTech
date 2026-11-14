package com.ddagtech.aureliabooks.controller.admin;

import com.ddagtech.aureliabooks.entity.Category;
import com.ddagtech.aureliabooks.service.MasterDataService;
import com.ddagtech.aureliabooks.util.CategoryTreePage;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CategoryPaginationTest {
    private Category category(long id, Category parent) {
        return Category.builder().id(id).name("Category " + id).parent(parent).isActive(true).build();
    }

    @Test
    void keepsDescendantsWithTheirRootAndFourthRootOnNextPage() {
        Category first = category(1, null), second = category(2, null), third = category(3, null);
        Category fourth = category(4, null), child = category(5, first), grandchild = category(6, child);
        Category fourthChild = category(7, fourth);
        List<Category> all = List.of(fourthChild, grandchild, fourth, child, third, second, first);
        var page1 = CategoryTreePage.from(all, 1);
        assertThat(page1.rows()).containsExactly(first, child, grandchild, second, third);
        assertThat(page1.roots().getTotalElements()).isEqualTo(4);
        assertThat(page1.roots().getTotalPages()).isEqualTo(2);
        assertThat(CategoryTreePage.from(all, 2).rows()).containsExactly(fourth, fourthChild);
        assertThat(CategoryTreePage.from(all, Integer.MAX_VALUE).rows()).containsExactly(fourth, fourthChild);
        assertThat(CategoryTreePage.from(all, -1).rows()).isEqualTo(page1.rows());
        assertThat(CategoryTreePage.from(List.of(), 1).rows()).isEmpty();
    }

    @Test
    void controllerKeepsAllParentChoicesButCountsOnlyDisplayedRows() {
        Category first = category(1, null), second = category(2, null), third = category(3, null);
        Category fourth = category(4, null), child = category(5, fourth);
        List<Category> all = List.of(first, second, third, fourth, child);
        MasterDataService service = mock(MasterDataService.class);
        when(service.getAllCategoriesWithParent()).thenReturn(all);
        var model = new ExtendedModelMap();
        assertThat(new MasterDataController(service).categories(2, model)).isEqualTo("admin/catalog/categories");
        assertThat(model.get("categories")).isEqualTo(List.of(fourth, child));
        assertThat(model.get("parentCategories")).isEqualTo(all);
        assertThat(model.get("totalCategories")).isEqualTo(5);
        verify(service, never()).countProductsByCategory(1L);
        verify(service).countProductsByCategory(4L);
        verify(service).countProductsByCategory(5L);
    }
}
