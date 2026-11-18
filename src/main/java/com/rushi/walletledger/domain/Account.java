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
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * A wallet account. The balance is a cached running total kept in the same row that we lock
 * for a transfer, so the check "is there enough money" and the update that spends it are one
 * atomic step. The authoritative history of how the balance came to be is the ledger entries.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Minor units (paise/cents): an integer, never a floating point number. */
    @Column(name = "balance", nullable = false)
    private long balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private AccountType type;

    /**
     * JPA optimistic-lock counter. It is also here as a second guard: a row that was changed
     * by someone we did not lock still fails the update instead of silently overwriting it.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA; application code must use the factory methods below. */
    protected Account() {
    }

    private Account(String ownerName, String currency, AccountType type) {
        this.ownerName = ownerName;
        this.currency = currency;
        this.type = type;
        this.balance = 0L;
    }

    public static Account userAccount(String ownerName, String currency) {
        return new Account(ownerName, currency, AccountType.USER);
    }

    public static Account systemAccount(String currency) {
        return new Account("treasury", currency, AccountType.SYSTEM);
    }

    /** A CREDIT to this account (money coming in). */
    public void credit(long amount) {
        this.balance = Math.addExact(this.balance, amount);
    }

    /** A DEBIT from this account. The caller must have checked the funds and holds the row lock. */
    public void debit(long amount) {
        this.balance = Math.subtractExact(this.balance, amount);
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

    public String getOwnerName() {
        return ownerName;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalance() {
        return balance;
    }

    public AccountType getType() {
        return type;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
