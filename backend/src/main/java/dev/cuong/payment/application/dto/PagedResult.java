package dev.cuong.payment.application.dto;

import java.util.List;

/**
 * Generic pagination wrapper returned by use cases that produce list results.
 * Plain Java record — no framework annotations — so it crosses all layer boundaries safely.
 */
public record PagedResult<T>(
        List<T> data,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
