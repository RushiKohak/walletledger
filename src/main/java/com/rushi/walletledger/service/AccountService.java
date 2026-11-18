package com.rushi.walletledger.service;

import com.rushi.walletledger.domain.Account;
import com.rushi.walletledger.error.AccountNotFoundException;
import com.rushi.walletledger.repository.AccountRepository;
import com.rushi.walletledger.web.dto.AccountResponse;
import com.rushi.walletledger.web.dto.CreateAccountRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account creation and retrieval. */
@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        // Accounts created through the API are always USER accounts with a zero balance. The
        // treasury is a SYSTEM account created by the first deposit in its currency, never here.
        Account account = accountRepository.save(Account.userAccount(request.ownerName(), request.currency()));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public AccountResponse get(long id) {
        return accountRepository.findById(id)
                .map(AccountResponse::from)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }
}
