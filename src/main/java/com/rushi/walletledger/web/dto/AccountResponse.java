package com.rushi.walletledger.web.dto;

import com.rushi.walletledger.domain.Account;
import com.rushi.walletledger.domain.AccountType;

/** An account as the API shows it: balance is minor units (paise/cents), never a decimal. */
public record AccountResponse(Long id,
                              String ownerName,
                              String currency,
                              long balance,
                              AccountType type) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(),
                account.getOwnerName(),
                account.getCurrency(),
                account.getBalance(),
                account.getType());
    }
}
