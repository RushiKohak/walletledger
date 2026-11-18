package com.rushi.walletledger.web.dto;

import com.rushi.walletledger.domain.EntryDirection;
import com.rushi.walletledger.repository.LedgerEntryView;

import java.time.Instant;
import java.util.UUID;

/**
 * One ledger entry as the API shows it. {@code amount} is minor units and always positive;
 * {@code direction} says which side of the double entry the account is on, so a client reads a
 * wallet statement as credits minus debits.
 */
public record LedgerEntryResponse(Long id,
                                  UUID transactionId,
                                  EntryDirection direction,
                                  long amount,
                                  Instant createdAt) {

    public static LedgerEntryResponse from(LedgerEntryView view) {
        return new LedgerEntryResponse(view.id(),
                view.transactionId(),
                view.direction(),
                view.amount(),
                view.createdAt());
    }
}
