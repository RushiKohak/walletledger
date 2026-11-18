package com.rushi.walletledger.error;

import org.springframework.http.HttpStatus;

import java.time.Instant;

/**
 * The one error shape every failing endpoint returns: {@code {status, error, message, timestamp}}.
 * Clients can therefore parse failures without special-casing which layer produced them.
 */
public record ErrorResponse(int status, String error, String message, Instant timestamp) {

    public static ErrorResponse of(HttpStatus status, String message) {
        return new ErrorResponse(status.value(), status.getReasonPhrase(), message, Instant.now());
    }
}
