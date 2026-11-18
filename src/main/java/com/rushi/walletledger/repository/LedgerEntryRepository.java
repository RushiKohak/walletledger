package com.rushi.walletledger.repository;

import com.rushi.walletledger.domain.LedgerEntry;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Persistence access for ledger entries.
 *
 * <p>This is deliberately a plain {@link Repository} and not a {@code JpaRepository}: the only
 * methods it declares are the ones the application actually calls, so there is no inherited
 * {@code delete} or {@code saveAndFlush} to reach for. Narrowing the interface is about keeping
 * the code honest, not about enforcement: only the database trigger installed by
 * {@code V2__ledger_entries_append_only.sql} can actually reject an UPDATE or a DELETE.
 */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    List<LedgerEntry> saveAll(Iterable<LedgerEntry> entries);
}
