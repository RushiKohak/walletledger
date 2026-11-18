package com.rushi.walletledger.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /api/v1/transfers}.
 *
 * <p>{@code amount} is a positive whole number of minor units, so a zero or negative transfer is
 * rejected here rather than becoming a money movement with no meaning. Whether the two accounts
 * are distinct, of the right type, in the right currency and funded enough is decided in the
 * service, because it needs the locked rows.
 */
public record TransferRequest(
        @NotNull(message = "is required")
        @Positive(message = "must be greater than 0")
        Long fromAccountId,

        @NotNull(message = "is required")
        @Positive(message = "must be greater than 0")
        Long toAccountId,

        @NotNull(message = "is required")
        @Positive(message = "must be greater than 0")
        Long amount,

        @NotBlank(message = "is required")
        String currency) {
}
