package com.rushi.walletledger.repository;

import com.rushi.walletledger.domain.LedgerTransaction;
import org.springframework.data.repository.Repository;

import java.util.UUID;

/**
 * Persistence access for ledger transactions.
 *
 * <p>Only the insert is exposed, and it flushes immediately: the entries reference this row
 * through a foreign key that Hibernate does not know about, so the row has to be in the database
 * before the entries are written.
 *
 * <p>The unique constraint on the idempotency key, not an application check, is what makes two
 * concurrent requests produce exactly one row.
 */
public interface LedgerTransactionRepository extends Repository<LedgerTransaction, UUID> {

    LedgerTransaction saveAndFlush(LedgerTransaction transaction);
}
