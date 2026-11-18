package com.rushi.walletledger.web.dto;

import java.util.UUID;

/**
 * Result of a transfer. Both balances are the values after the transaction committed, so a client
 * can display the outcome without a follow-up read.
 */
public record TransferResponse(UUID transactionId,
                               Long fromAccountId,
                               Long toAccountId,
                               long amount,
                               String currency,
                               long fromBalanceAfter,
                               long toBalanceAfter) {
}
