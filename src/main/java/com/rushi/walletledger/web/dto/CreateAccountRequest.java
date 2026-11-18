package com.rushi.walletledger.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/accounts}.
 *
 * <p>The currency is validated as exactly three uppercase letters because that is what the
 * database {@code CHECK} also enforces, and because a lowercase or longer code would silently
 * become a different bucket of money.
 */
public record CreateAccountRequest(
        @NotBlank(message = "is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String ownerName,

        @NotBlank(message = "is required")
        @Pattern(regexp = "[A-Z]{3}", message = "must be exactly 3 uppercase letters")
        String currency) {
}
