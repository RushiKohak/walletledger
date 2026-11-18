package com.rushi.walletledger.service;

import com.rushi.walletledger.domain.Account;
import com.rushi.walletledger.domain.AccountType;
import com.rushi.walletledger.domain.LedgerEntry;
import com.rushi.walletledger.domain.LedgerTransaction;
import com.rushi.walletledger.domain.TransactionType;
import com.rushi.walletledger.error.AccountNotFoundException;
import com.rushi.walletledger.error.InsufficientFundsException;
import com.rushi.walletledger.error.InvalidRequestException;
import com.rushi.walletledger.repository.AccountIdentity;
import com.rushi.walletledger.repository.AccountRepository;
import com.rushi.walletledger.repository.LedgerEntryRepository;
import com.rushi.walletledger.repository.LedgerTransactionRepository;
import com.rushi.walletledger.web.dto.TransferRequest;
import com.rushi.walletledger.web.dto.TransferResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Moves money between two user wallets.
 *
 * <p>Nothing is created and nothing is destroyed: the sender is debited and the receiver is
 * credited by the same amount inside one transaction, so the total money in the system is
 * unchanged and the two entries of the transfer always sum to zero.
 */
@Service
public class TransferService {

    /** {@code ledger_transactions.idempotency_key} is VARCHAR(255). */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public TransferService(AccountRepository accountRepository,
                           LedgerTransactionRepository ledgerTransactionRepository,
                           LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * One transfer is one database transaction: both locks, both balance updates, the transaction
     * row and both entries commit together or not at all, so a rejected or crashed transfer leaves
     * no trace and no half-applied money.
     */
    @Transactional
    public TransferResponse transfer(String idempotencyKey, TransferRequest request) {
        String key = requireIdempotencyKey(idempotencyKey);
        long amount = request.amount();
        Long fromId = request.fromAccountId();
        Long toId = request.toAccountId();

        // Checked before any database work: this is a property of the request alone, and a
        // self-transfer would otherwise lock the same row twice and "move" money to itself.
        if (fromId.equals(toId)) {
            throw new InvalidRequestException("fromAccountId and toAccountId must be different accounts");
        }

        // Identity projections only. No Account entity may be in the persistence context before
        // its row is locked, otherwise Hibernate could later write back a balance that was read
        // before the lock was taken.
        AccountIdentity from = accountRepository.findIdentityById(fromId)
                .orElseThrow(() -> new AccountNotFoundException(fromId));
        AccountIdentity to = accountRepository.findIdentityById(toId)
                .orElseThrow(() -> new AccountNotFoundException(toId));

        // Both rows are locked in ascending id order, the same order every money movement uses.
        // A consistent order is what makes a deadlock between two opposite transfers impossible:
        // A->B and B->A can only ever queue behind each other, never cross.
        Account firstLocked = lockAccount(Math.min(from.id(), to.id()));
        Account secondLocked = lockAccount(Math.max(from.id(), to.id()));
        Account sender = firstLocked.getId().equals(from.id()) ? firstLocked : secondLocked;
        Account receiver = firstLocked.getId().equals(from.id()) ? secondLocked : firstLocked;

        // Every rule is checked against the locked rows, so the values validated are the values
        // written: nothing can change underneath between the check and the update.
        requireUserAccount(sender, "fromAccountId");
        requireUserAccount(receiver, "toAccountId");
        requireCurrency(sender, request.currency(), "fromAccountId");
        requireCurrency(receiver, request.currency(), "toAccountId");
        if (sender.getBalance() < amount) {
            throw new InsufficientFundsException(sender.getId(), sender.getBalance(), amount);
        }

        // Flushed before the entries because the entries reference it through a foreign key that
        // Hibernate does not know about (entries hold a plain id, not an association).
        LedgerTransaction transaction = LedgerTransaction.posted(
                UUID.randomUUID(),
                key,
                RequestHash.sha256(canonicalRequest(fromId, toId, amount, request.currency())),
                TransactionType.TRANSFER);
        ledgerTransactionRepository.saveAndFlush(transaction);

        // The sender loses exactly what the receiver gains. Both use Math.addExact/subtractExact,
        // so an overflow raises an error (mapped to 400) instead of wrapping a balance around.
        sender.debit(amount);
        receiver.credit(amount);

        ledgerEntryRepository.saveAll(List.of(
                LedgerEntry.debit(transaction.getId(), sender.getId(), amount),
                LedgerEntry.credit(transaction.getId(), receiver.getId(), amount)));

        return new TransferResponse(
                transaction.getId(),
                sender.getId(),
                receiver.getId(),
                amount,
                request.currency(),
                sender.getBalance(),
                receiver.getBalance());
    }

    private Account lockAccount(Long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalStateException("account " + accountId + " disappeared mid-transaction"));
    }

    private static void requireUserAccount(Account account, String field) {
        if (account.getType() != AccountType.USER) {
            throw new InvalidRequestException(field + " must be a USER account, not a SYSTEM account");
        }
    }

    private static void requireCurrency(Account account, String currency, String field) {
        if (!account.getCurrency().equals(currency)) {
            throw new InvalidRequestException(field + " currency " + account.getCurrency()
                    + " does not match the request currency " + currency);
        }
    }

    /**
     * Fixed field order and no JSON formatting: the same transfer always produces the same hash,
     * whatever ordering or whitespace the client used in the body it sent.
     */
    private static String canonicalRequest(long fromAccountId, long toAccountId, long amount, String currency) {
        return "TRANSFER|" + fromAccountId + "|" + toAccountId + "|" + amount + "|" + currency;
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
