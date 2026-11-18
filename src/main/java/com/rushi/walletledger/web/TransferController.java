package com.rushi.walletledger.web;

import com.rushi.walletledger.service.TransferService;
import com.rushi.walletledger.web.dto.TransferRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for transfers. It only translates HTTP into a service call. */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * 201 when the transfer happened, 200 with {@code Idempotent-Replayed: true} when the request
     * is a retry of a transfer that already happened with this key.
     */
    @PostMapping
    public ResponseEntity<String> transfer(
            // Optional here so a missing header produces our own 400 error body rather than a
            // container error page. The service is what decides it is required.
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return MoneyMovementHttp.respond(transferService.transfer(idempotencyKey, request));
    }
}
