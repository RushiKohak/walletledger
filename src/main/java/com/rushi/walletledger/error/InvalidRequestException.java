package com.rushi.walletledger.error;

/**
 * Thrown when a request is well-formed JSON but asks for something the business rules forbid,
 * such as depositing into the treasury or using a currency the account does not hold. Mapped to 400.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
