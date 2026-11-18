package com.rushi.walletledger.web;

import com.rushi.walletledger.service.DepositService;
import com.rushi.walletledger.web.dto.DepositRequest;
import com.rushi.walletledger.web.dto.DepositResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for deposits. It only translates HTTP into a service call. */
@RestController
@RequestMapping("/api/v1/deposits")
public class DepositController {

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DepositResponse deposit(
            // Optional here so a missing header produces our own 400 error body rather than a
            // container error page. The service is what decides it is required.
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        return depositService.deposit(idempotencyKey, request);
    }
}
