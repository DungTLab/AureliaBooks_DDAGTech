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

    @Test
    void preservesOrphanBranchesAndCyclesWithoutValidRoots() {
        Category orphan = category(3, category(99, null));
        Category child = category(1, orphan);
        Category cycleA = category(4, null), cycleB = category(5, cycleA);
        cycleA.setParent(cycleB);

        var page = CategoryTreePage.from(List.of(child, orphan, cycleA, cycleB), 1);

        assertThat(page.rows()).extracting(Category::getId).containsExactly(3L, 1L, 4L, 5L);
        assertThat(page.roots().getTotalElements()).isEqualTo(2);
        assertThat(page.roots().getTotalPages()).isEqualTo(1);
    }

    @Test
    void keepsCycleWithLowerIdDescendantsTogetherOnTheNextPage() {
        Category first = category(10, null), second = category(20, null), third = category(30, null);
        Category cycleA = category(40, null), cycleB = category(50, cycleA);
        cycleA.setParent(cycleB);
        Category child = category(1, cycleA), grandchild = category(2, child);
        List<Category> all = List.of(child, grandchild, cycleA, cycleB, first, second, third);

        var page1 = CategoryTreePage.from(all, 1);
        var page2 = CategoryTreePage.from(all, 2);

        assertThat(page1.rows()).extracting(Category::getId).containsExactly(10L, 20L, 30L);
        assertThat(page2.rows()).extracting(Category::getId).containsExactly(40L, 1L, 2L, 50L);
        assertThat(page1.roots().getTotalElements()).isEqualTo(4);
        assertThat(page1.roots().getTotalPages()).isEqualTo(2);
        var displayedIds = java.util.stream.Stream.concat(page1.rows().stream(), page2.rows().stream())
                .map(Category::getId).toList();
        assertThat(displayedIds).doesNotHaveDuplicates().containsExactlyInAnyOrder(1L, 2L, 10L, 20L, 30L, 40L, 50L);
        assertThat(CategoryTreePage.from(all, Integer.MAX_VALUE).rows()).isEqualTo(page2.rows());
    }

    @Test
    void controllerCountsAndDisplaysAbnormalComponents() {
        Category orphan = category(3, category(99, null));
        Category child = category(1, orphan);
        Category cycle = category(4, null);
        cycle.setParent(cycle);
        List<Category> all = List.of(child, orphan, cycle);
        MasterDataService service = mock(MasterDataService.class);
        when(service.getAllCategoriesWithParent()).thenReturn(all);
        var model = new ExtendedModelMap();

        new MasterDataController(service).categories(1, model);

        assertThat(model.get("categories")).isEqualTo(List.of(orphan, child, cycle));
        assertThat(model.get("totalCategories")).isEqualTo(3);
        verify(service).countProductsByCategory(1L);
        verify(service).countProductsByCategory(3L);
        verify(service).countProductsByCategory(4L);
    }

    @Test
    void controllerProvidesDepthsForMultipleLevelsAndMalformedBranches() {
        Category root = category(10, null), child = category(11, root), grandchild = category(12, child);
        Category orphan = category(20, category(99, null)), orphanChild = category(21, orphan);
        Category cycleA = category(30, null), cycleB = category(31, cycleA);
        cycleA.setParent(cycleB);
        Category selfCycle = category(40, null);
        selfCycle.setParent(selfCycle);
        List<Category> all = List.of(root, child, grandchild, orphan, orphanChild, cycleA, cycleB, selfCycle);
        MasterDataService service = mock(MasterDataService.class);
        when(service.getAllCategoriesWithParent()).thenReturn(all);
        var model = new ExtendedModelMap();

        new MasterDataController(service).categories(1, model);

        assertThat(model.get("categoryDepths")).isEqualTo(java.util.Map.of(
                10L, 0, 11L, 1, 12L, 2, 20L, 0, 21L, 1, 30L, 1, 31L, 1));
        var secondPage = new ExtendedModelMap();
        new MasterDataController(service).categories(2, secondPage);
        assertThat(secondPage.get("categoryDepths")).isEqualTo(java.util.Map.of(40L, 0));
    }
}
