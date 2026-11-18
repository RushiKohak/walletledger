package com.rushi.walletledger.web;

import com.rushi.walletledger.service.AccountService;
import com.rushi.walletledger.service.LedgerEntryService;
import com.rushi.walletledger.web.dto.AccountResponse;
import com.rushi.walletledger.web.dto.CreateAccountRequest;
import com.rushi.walletledger.web.dto.LedgerEntryResponse;
import com.rushi.walletledger.web.dto.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for wallet accounts. It only translates HTTP into service calls. */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final LedgerEntryService ledgerEntryService;

    public AccountController(AccountService accountService, LedgerEntryService ledgerEntryService) {
        this.accountService = accountService;
        this.ledgerEntryService = ledgerEntryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return accountService.create(request);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable long id) {
        return accountService.get(id);
    }

    /**
     * A page of the account's ledger entries, newest first.
     *
     * <p>The page size is a parameter rather than a constant so a client can ask for a smaller
     * page; the service enforces the range and defaults, so an out-of-range value is the same 400
     * error body the rest of the API returns.
     */
    @GetMapping("/{id}/entries")
    public PageResponse<LedgerEntryResponse> entries(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ledgerEntryService.entries(id, page, size);
    }
}
