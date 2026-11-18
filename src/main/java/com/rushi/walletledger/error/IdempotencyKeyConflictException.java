package com.rushi.walletledger.error;

/**
 * Thrown when an idempotency key is reused for a different request. The key identifies one
 * operation, so answering the second request with the first one's result would silently apply the
 * wrong movement. Mapped to 422: the body is well-formed JSON, but the key and the request body
 * contradict each other.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("idempotency key " + idempotencyKey + " was already used for a different request");
    }
}
