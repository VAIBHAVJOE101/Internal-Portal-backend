package com.platform.portal.common;

import java.util.List;

import org.springframework.data.domain.Page;

public record PageResult<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResult<T> of(Page<T> page) {
        return new PageResult<>(page.getContent(), page.getTotalElements(), page.getNumber(), page.getSize());
    }

    public static <T> PageResult<T> slice(List<T> all, int page, int size) {
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageResult<>(all.subList(from, to), all.size(), page, size);
    }
}
