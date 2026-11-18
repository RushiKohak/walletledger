package com.rushi.walletledger.web;

import com.rushi.walletledger.service.MoneyMovementResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Turns a money movement result into the HTTP response, in one place so deposits and transfers
 * cannot drift apart: 201 for a movement that just happened, 200 plus {@code Idempotent-Replayed}
 * for a replay, and always the stored JSON body verbatim.
 */
final class MoneyMovementHttp {

    private MoneyMovementHttp() {
    }

    static ResponseEntity<String> respond(MoneyMovementResult result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON);
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(result.responseBody());
    }
}
