package com.rushi.walletledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One side of a double entry. Entries are append-only: they are inserted once with their
 * transaction and are never updated or deleted, so the ledger history cannot be rewritten.
 *
 * <p>Foreign keys are held as plain ids instead of {@code @ManyToOne} associations because an
 * entry only needs to point at its transaction and account, and never to navigate back.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private EntryDirection direction;

    /** Minor units, always positive; {@link #direction} carries the sign. */
    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected LedgerEntry() {
    }

    private LedgerEntry(UUID transactionId, Long accountId, EntryDirection direction, long amount) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
    }

    public static LedgerEntry debit(UUID transactionId, Long accountId, long amount) {
        return new LedgerEntry(transactionId, accountId, EntryDirection.DEBIT, amount);
    }

    public static LedgerEntry credit(UUID transactionId, Long accountId, long amount) {
        return new LedgerEntry(transactionId, accountId, EntryDirection.CREDIT, amount);
    }

    @PrePersist
    void assignCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
