package com.ddagtech.aureliabooks.util;

import com.ddagtech.aureliabooks.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Management-only pagination: each category component stays together. */
public record CategoryTreePage(Page<Category> roots, List<Category> rows) {
    public static CategoryTreePage from(List<Category> categories, int requestedPage) {
        List<Category> ordered = categories.stream().sorted(Comparator.comparing(Category::getId)).toList();
        Map<Long, Category> byId = new HashMap<>();
        Map<Long, List<Category>> children = new HashMap<>();
        for (Category category : ordered) {
            byId.put(category.getId(), category);
            if (category.getParent() != null) {
                children.computeIfAbsent(category.getParent().getId(), key -> new ArrayList<>()).add(category);
            }
        }

        List<Category> representatives = new ArrayList<>();
        List<List<Category>> groups = new ArrayList<>();
        Set<Long> visited = new HashSet<>();

        // Keep normal roots first, then branches whose parent is absent from the input.
        for (Category category : ordered) {
            if (category.getParent() == null) {
                addGroup(category, children, visited, representatives, groups);
            }
        }
        for (Category category : ordered) {
            if (category.getParent() != null && !byId.containsKey(category.getParent().getId())) {
                addGroup(category, children, visited, representatives, groups);
            }
        }

        // Follow parents into a cycle before traversing children, keeping its entire component together.
        for (Category category : ordered) {
            if (visited.contains(category.getId())) {
                continue;
            }
            Category start = category;
            Set<Long> ancestors = new HashSet<>();
            while (ancestors.add(start.getId()) && start.getParent() != null
                    && byId.containsKey(start.getParent().getId())) {
                start = byId.get(start.getParent().getId());
            }
            addGroup(start, children, visited, representatives, groups);
        }

        int pageSize = 3;
        int lastPage = Math.max(0, (groups.size() - 1) / pageSize);
        int page = Math.max(0, Math.min(Math.max(1, requestedPage) - 1, lastPage));
        int from = page * pageSize;
        int to = Math.min(from + pageSize, groups.size());
        List<Category> rows = new ArrayList<>();
        for (int index = from; index < to; index++) {
            rows.addAll(groups.get(index));
        }
        return new CategoryTreePage(
                new PageImpl<>(representatives.subList(from, to), PageRequest.of(page, pageSize), groups.size()),
                List.copyOf(rows));
    }

    private static void addGroup(Category start, Map<Long, List<Category>> children, Set<Long> visited,
                                 List<Category> representatives, List<List<Category>> groups) {
        if (visited.contains(start.getId())) {
            return;
        }
        List<Category> group = new ArrayList<>();
        Deque<Category> pending = new ArrayDeque<>();
        pending.push(start);
        while (!pending.isEmpty()) {
            Category current = pending.pop();
            if (!visited.add(current.getId())) {
                continue;
            }
            group.add(current);
            List<Category> descendants = children.getOrDefault(current.getId(), List.of());
            // Reverse insertion preserves ascending sibling IDs when popping the stack.
            for (int index = descendants.size() - 1; index >= 0; index--) {
                pending.push(descendants.get(index));
            }
        }
        representatives.add(start);
        groups.add(List.copyOf(group));
    }
}
