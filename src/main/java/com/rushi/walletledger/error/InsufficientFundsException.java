package com.rushi.walletledger.error;

/**
 * Thrown when a transfer asks to spend more than the sender's balance. Mapped to 409 Conflict:
 * the request is not malformed, it simply conflicts with the state of the account at the moment
 * it was evaluated, and the same request could succeed later once the account is funded.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(Long accountId, long balance, long requestedAmount) {
        super("insufficient funds in account " + accountId + ": balance " + balance
                + " is less than the requested amount " + requestedAmount);
    }
}
