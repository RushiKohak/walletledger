package com.rushi.walletledger.service;

import com.rushi.walletledger.domain.Account;
import com.rushi.walletledger.domain.AccountType;
import com.rushi.walletledger.domain.LedgerEntry;
import com.rushi.walletledger.domain.LedgerTransaction;
import com.rushi.walletledger.domain.TransactionType;
import com.rushi.walletledger.error.AccountNotFoundException;
import com.rushi.walletledger.error.InvalidRequestException;
import com.rushi.walletledger.repository.AccountIdentity;
import com.rushi.walletledger.repository.AccountRepository;
import com.rushi.walletledger.repository.LedgerEntryRepository;
import com.rushi.walletledger.repository.LedgerTransactionRepository;
import com.rushi.walletledger.web.dto.DepositRequest;
import com.rushi.walletledger.web.dto.DepositResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Deposits money into a user wallet.
 *
 * <p>A deposit is not "add to a number": it is a balanced double entry that debits the treasury
 * account of the currency and credits the user, so every rupee in a user wallet has a matching
 * negative rupee in the treasury and the books always sum to zero.
 */
@Service
public class DepositService {

    /** {@code ledger_transactions.idempotency_key} is VARCHAR(255). */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public DepositService(AccountRepository accountRepository,
                          LedgerTransactionRepository ledgerTransactionRepository,
                          LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * One deposit is one database transaction: the ledger transaction row, both ledger entries and
     * both balance updates commit together or not at all, so a crash can never leave a balance
     * that disagrees with the entries.
     */
    @Transactional
    public DepositResponse deposit(String idempotencyKey, DepositRequest request) {
        String key = requireIdempotencyKey(idempotencyKey);
        long amount = request.amount();

        AccountIdentity target = accountRepository.findIdentityById(request.accountId())
                .orElseThrow(() -> new AccountNotFoundException(request.accountId()));
        if (target.type() != AccountType.USER) {
            throw new InvalidRequestException("deposits must target a USER account, not a SYSTEM account");
        }
        if (!target.currency().equals(request.currency())) {
            throw new InvalidRequestException("currency " + request.currency()
                    + " does not match the account currency " + target.currency());
        }

        Long treasuryId = treasuryIdFor(target.currency());

        // Both rows are locked in ascending id order, and every money movement in the system locks
        // in that same order. A consistent order is what makes a deadlock between two concurrent
        // movements impossible: they can only ever queue behind each other.
        Account firstLocked = lockAccount(Math.min(target.id(), treasuryId));
        Account secondLocked = lockAccount(Math.max(target.id(), treasuryId));
        Account user = firstLocked.getType() == AccountType.USER ? firstLocked : secondLocked;
        Account treasury = user == firstLocked ? secondLocked : firstLocked;

        // Flushed before the entries are inserted because the entries reference it through a
        // foreign key and Hibernate does not know about that dependency (entries hold a plain id).
        LedgerTransaction transaction = LedgerTransaction.posted(
                UUID.randomUUID(),
                key,
                RequestHash.sha256(canonicalRequest(target.id(), amount, target.currency())),
                TransactionType.DEPOSIT);
        ledgerTransactionRepository.saveAndFlush(transaction);

        // What the user gains, the treasury loses. Both sides use Math.addExact/subtractExact, so
        // an overflow raises an error (mapped to 400) instead of silently wrapping the balance.
        treasury.debit(amount);
        user.credit(amount);

        ledgerEntryRepository.saveAll(List.of(
                LedgerEntry.debit(transaction.getId(), treasury.getId(), amount),
                LedgerEntry.credit(transaction.getId(), user.getId(), amount)));

        return new DepositResponse(transaction.getId(), user.getId(), amount, target.currency(), user.getBalance());
    }

    /**
     * Finds the treasury of a currency, creating it if this is the first deposit in that currency.
     * The database's partial unique index decides who creates it; losers of the race re-read the
     * row the winner committed.
     */
    private Long treasuryIdFor(String currency) {
        accountRepository.insertTreasuryIfAbsent(currency);
        return accountRepository.findIdByCurrencyAndType(currency, AccountType.SYSTEM)
                .orElseThrow(() -> new IllegalStateException("no treasury account for " + currency));
    }

    private Account lockAccount(Long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalStateException("account " + accountId + " disappeared mid-transaction"));
    }

    /**
     * Fixed field order and no JSON formatting: the same deposit always produces the same hash,
     * whatever ordering or whitespace the client used in the body it sent.
     */
    private static String canonicalRequest(long accountId, long amount, String currency) {
        return "DEPOSIT|" + accountId + "|" + amount + "|" + currency;
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidRequestException("the Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidRequestException(
                    "the Idempotency-Key header must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return idempotencyKey;
    }
}
