package com.ddagtech.aureliabooks.util;

import com.ddagtech.aureliabooks.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import java.util.*;

/** Management-only pagination: a root and all descendants stay together. */
public record CategoryTreePage(Page<Category> roots, List<Category> rows) {
    public static CategoryTreePage from(List<Category> categories, int requestedPage) {
        List<Category> ordered = categories.stream().sorted(Comparator.comparing(Category::getId)).toList();
        List<Category> roots = ordered.stream().filter(c -> c.getParent() == null).toList();
        int page = Math.max(0, Math.min(Math.max(1, requestedPage) - 1, Math.max(0, (roots.size() - 1) / 3)));
        int start = page * 3;
        List<Category> selected = roots.subList(start, Math.min(start + 3, roots.size()));
        Map<Long, List<Category>> children = new HashMap<>();
        for (Category category : ordered) {
            if (category.getParent() != null) children.computeIfAbsent(category.getParent().getId(), k -> new ArrayList<>()).add(category);
        }
        List<Category> rows = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Deque<Category> pending = new ArrayDeque<>();
        for (Category root : selected) {
            pending.push(root);
            while (!pending.isEmpty()) {
                Category current = pending.pop();
                if (!visited.add(current.getId())) continue;
                rows.add(current);
                List<Category> descendants = children.getOrDefault(current.getId(), List.of());
                for (int i = descendants.size() - 1; i >= 0; i--) pending.push(descendants.get(i));
            }
        }
        return new CategoryTreePage(new PageImpl<>(selected, PageRequest.of(page, 3), roots.size()), List.copyOf(rows));
    }
}
