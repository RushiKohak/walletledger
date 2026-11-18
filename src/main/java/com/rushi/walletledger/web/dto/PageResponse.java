package com.rushi.walletledger.web.dto;

import java.util.List;

/**
 * The page envelope the API returns, with names a client can use directly.
 *
 * <p>Spring's own {@code Page} is not serialised as-is: it is an interface with an unstable JSON
 * shape, so the fields a client depends on are spelled out here instead.
 *
 * @param page zero-based index of this page
 * @param size  the page size that was asked for, echoed back so a client can tell a defaulted
 *              value from the one it sent
 */
public record PageResponse<T>(List<T> content,
                              int page,
                              int size,
                              long totalElements,
                              int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size,
                                         long totalElements, int totalPages) {
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}
