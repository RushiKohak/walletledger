package com.rushi.walletledger.error;

/** Thrown when an endpoint is asked about an account id that does not exist. Mapped to 404. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long accountId) {
        super("account " + accountId + " does not exist");
    }
}
