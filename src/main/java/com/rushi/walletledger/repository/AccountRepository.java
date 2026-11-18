package com.rushi.walletledger.repository;

import com.rushi.walletledger.domain.Account;
import com.rushi.walletledger.domain.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Persistence access for wallet accounts. */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Cheap read of the columns needed to choose the rows to lock, without putting an
     * {@link Account} in the persistence context. The locked reads below must be the ones that
     * load the balances, otherwise we could write back a balance read before the lock was taken.
     */
    @Query("""
            select new com.rushi.walletledger.repository.AccountIdentity(a.id, a.currency, a.type)
            from Account a
            where a.id = :id
            """)
    Optional<AccountIdentity> findIdentityById(@Param("id") Long id);

    /**
     * {@code SELECT ... FOR UPDATE}: the row stays locked until the surrounding transaction ends,
     * so the balance read here cannot be changed by a concurrent deposit or transfer, and the
     * check "is there enough money" plus the update that spends it are one atomic step.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /**
     * Creates the treasury row for a currency unless one already exists.
     *
     * <p>The partial unique index on {@code (currency) WHERE type = 'SYSTEM'} is the arbiter: when
     * two concurrent first deposits in the same currency race, one statement inserts and the other
     * becomes a no-op instead of failing. Catching the constraint violation inside the same
     * transaction would not work, because PostgreSQL aborts the whole transaction on the error.
     */
    @Modifying
    @Query(value = """
            insert into accounts (owner_name, currency, balance, type, version, created_at)
            values ('treasury', :currency, 0, 'SYSTEM', 0, now())
            on conflict (currency) where type = 'SYSTEM' do nothing
            """, nativeQuery = true)
    int insertTreasuryIfAbsent(@Param("currency") String currency);

    @Query("select a.id from Account a where a.currency = :currency and a.type = :type")
    Optional<Long> findIdByCurrencyAndType(@Param("currency") String currency,
                                           @Param("type") AccountType type);
}
