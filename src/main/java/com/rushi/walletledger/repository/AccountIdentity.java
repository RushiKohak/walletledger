package com.rushi.walletledger.repository;

import com.rushi.walletledger.domain.AccountType;

/**
 * A read-only view of the three account columns a money movement needs in order to decide which
 * rows to lock. It is deliberately not an entity: loading an {@code Account} before taking the
 * row lock could leave a stale copy in the persistence context, and the balance we later write
 * back would be that stale value.
 */
public record AccountIdentity(Long id, String currency, AccountType type) {
}
