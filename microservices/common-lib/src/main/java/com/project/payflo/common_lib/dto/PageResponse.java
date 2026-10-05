package com.project.payflo.common_lib.dto;

import org.springframework.data.domain.Slice;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a list endpoint. There is deliberately no total: counting a merchant's millions of payments
 * on every request is the expensive part of paging, and "is there a next page" is all a client needs to walk
 * the list.
 */
public record PageResponse<T>(List<T> items, int page, int size, boolean hasNext) {

    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 20;
    // Offset paging gets slower the deeper it goes; nobody needs to walk past this by page number.
    public static final int MAX_PAGE = 1000;

    public static <E, T> PageResponse<T> of(Slice<E> slice, Function<E, T> mapper) {
        return new PageResponse<>(slice.getContent().stream().map(mapper).toList(),
                slice.getNumber(), slice.getSize(), slice.hasNext());
    }

    public static int clampPage(int page) {
        return Math.min(Math.max(page, 0), MAX_PAGE);
    }

    public static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }
}
