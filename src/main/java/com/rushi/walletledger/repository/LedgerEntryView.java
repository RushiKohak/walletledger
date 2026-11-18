package com.rushi.walletledger.repository;

import com.rushi.walletledger.domain.EntryDirection;

import java.time.Instant;
import java.util.UUID;

/**
 * The five columns the entries endpoint shows, read straight out of the database.
 *
 * <p>It is a projection and not a {@code LedgerEntry} entity on purpose: a page of history is a
 * read-only report, so there is nothing to dirty-check, nothing to lazily load, and no managed
 * entity left in the persistence context after the transaction ends.
 */
public record LedgerEntryView(Long id,
                              UUID transactionId,
                              EntryDirection direction,
                              long amount,
                              Instant createdAt) {
}
