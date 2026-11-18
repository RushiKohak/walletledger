package com.rushi.walletledger.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /api/v1/deposits}.
 *
 * <p>{@code amount} is a positive whole number of minor units, so a zero or negative deposit is
 * rejected here rather than becoming a money movement with no meaning.
 */
public record DepositRequest(
        @NotNull(message = "is required")
        @Positive(message = "must be greater than 0")
        Long accountId,

        @NotNull(message = "is required")
        @Positive(message = "must be greater than 0")
        Long amount,

        @NotBlank(message = "is required")
        String currency) {
}
