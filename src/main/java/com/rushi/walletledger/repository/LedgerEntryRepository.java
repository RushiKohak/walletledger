package com.rushi.walletledger.repository;

import com.rushi.walletledger.domain.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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

    /**
     * One page of an account's history, newest first.
     *
     * <p>The query selects into {@link LedgerEntryView} rather than into the entity, so a page of
     * 20 rows loads 20 rows of five columns and nothing else. The order is part of the query, not
     * of the {@link Pageable}, so a caller cannot accidentally ask for a different page order and
     * get rows that shift between pages. The count query is written out because the projection is
     * not something Spring Data can derive a count from on its own.
     */
    @Query(value = """
            select new com.rushi.walletledger.repository.LedgerEntryView(
                       e.id, e.transactionId, e.direction, e.amount, e.createdAt)
            from LedgerEntry e
            where e.accountId = :accountId
            order by e.id desc
            """,
            countQuery = """
                    select count(e)
                    from LedgerEntry e
                    where e.accountId = :accountId
                    """)
    Page<LedgerEntryView> findViewsByAccountId(@Param("accountId") Long accountId, Pageable pageable);
}
