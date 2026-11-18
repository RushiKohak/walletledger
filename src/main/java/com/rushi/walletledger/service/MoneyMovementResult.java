package com.rushi.walletledger.service;

/**
 * The outcome of a money-moving request: the JSON body to send back, and whether it is a replay
 * of an earlier request with the same idempotency key rather than a movement that just happened.
 *
 * <p>The body is stored with the transaction, so a replay returns byte-for-byte what the original
 * request returned instead of re-deriving it from state that may since have changed.
 */
public record MoneyMovementResult(String responseBody, boolean replayed) {
}
