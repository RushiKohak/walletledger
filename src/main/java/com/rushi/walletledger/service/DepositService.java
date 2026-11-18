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
 *
 * <p>Each deposit is identified by its idempotency key, so retrying a request that already
 * succeeded replays its original response instead of depositing twice.
 */
@Service
public class DepositService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyService idempotency;

    public DepositService(AccountRepository accountRepository,
                          LedgerTransactionRepository ledgerTransactionRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          IdempotencyService idempotency) {
        this.accountRepository = accountRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotency = idempotency;
    }

    /**
     * One deposit is one database transaction: the ledger transaction row, both ledger entries and
     * both balance updates commit together or not at all, so a crash can never leave a balance
     * that disagrees with the entries.
     */
    @Transactional
    public MoneyMovementResult deposit(String idempotencyKey, DepositRequest request) {
        long amount = request.amount();
        String requestHash = RequestHash.sha256(
                canonicalRequest(request.accountId(), amount, request.currency()));

        return idempotency.execute(idempotencyKey, requestHash, key -> {
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

            // Both rows are locked in ascending id order, and every money movement in the system
            // locks in that same order. A consistent order is what makes a deadlock between two
            // concurrent movements impossible: they can only ever queue behind each other.
            Account firstLocked = lockAccount(Math.min(target.id(), treasuryId));
            Account secondLocked = lockAccount(Math.max(target.id(), treasuryId));
            Account user = firstLocked.getType() == AccountType.USER ? firstLocked : secondLocked;
            Account treasury = user == firstLocked ? secondLocked : firstLocked;

            // What the user gains, the treasury loses. Both sides use Math.addExact/subtractExact,
            // so an overflow raises an error (mapped to 400) instead of silently wrapping.
            treasury.debit(amount);
            user.credit(amount);

            UUID transactionId = UUID.randomUUID();
            String responseBody = idempotency.json(new DepositResponse(
                    transactionId, user.getId(), amount, target.currency(), user.getBalance()));

            // Flushed before the entries are inserted because the entries reference it through a
            // foreign key and Hibernate does not know about that dependency (entries hold a plain id).
            // The response body is written with the row, which is why a replay needs no other source.
            ledgerTransactionRepository.saveAndFlush(LedgerTransaction.posted(
                    transactionId, key, requestHash, TransactionType.DEPOSIT, responseBody));

            ledgerEntryRepository.saveAll(List.of(
                    LedgerEntry.debit(transactionId, treasury.getId(), amount),
                    LedgerEntry.credit(transactionId, user.getId(), amount)));

            return responseBody;
        });
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
}
