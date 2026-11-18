package com.rushi.walletledger.web.dto;

import java.util.UUID;

/**
 * Result of a deposit. {@code transactionId} identifies the ledger transaction that was written,
 * which is what makes the effect of the request auditable, and {@code balanceAfter} is the
 * balance of the target account once that transaction committed.
 */
public record DepositResponse(UUID transactionId,
                              Long accountId,
                              long amount,
                              String currency,
                              long balanceAfter) {
}
