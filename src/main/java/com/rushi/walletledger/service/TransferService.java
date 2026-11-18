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
 *
 * <p>Each transfer is identified by its idempotency key, so retrying a request that already
 * succeeded replays its original response instead of moving the money again.
 */
@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyService idempotency;

    public TransferService(AccountRepository accountRepository,
                           LedgerTransactionRepository ledgerTransactionRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           IdempotencyService idempotency) {
        this.accountRepository = accountRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotency = idempotency;
    }

    /**
     * One transfer is one database transaction: both locks, both balance updates, the transaction
     * row and both entries commit together or not at all, so a rejected or crashed transfer leaves
     * no trace and no half-applied money.
     */
    @Transactional
    public MoneyMovementResult transfer(String idempotencyKey, TransferRequest request) {
        long amount = request.amount();
        Long fromId = request.fromAccountId();
        Long toId = request.toAccountId();
        String requestHash = RequestHash.sha256(canonicalRequest(fromId, toId, amount, request.currency()));

        return idempotency.execute(idempotencyKey, requestHash, key -> {
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

            // The sender loses exactly what the receiver gains. Both use Math.addExact/subtractExact,
            // so an overflow raises an error (mapped to 400) instead of wrapping a balance around.
            sender.debit(amount);
            receiver.credit(amount);

            UUID transactionId = UUID.randomUUID();
            String responseBody = idempotency.json(new TransferResponse(
                    transactionId,
                    sender.getId(),
                    receiver.getId(),
                    amount,
                    request.currency(),
                    sender.getBalance(),
                    receiver.getBalance()));

            // Flushed before the entries because the entries reference it through a foreign key that
            // Hibernate does not know about (entries hold a plain id, not an association).
            ledgerTransactionRepository.saveAndFlush(LedgerTransaction.posted(
                    transactionId, key, requestHash, TransactionType.TRANSFER, responseBody));

            ledgerEntryRepository.saveAll(List.of(
                    LedgerEntry.debit(transactionId, sender.getId(), amount),
                    LedgerEntry.credit(transactionId, receiver.getId(), amount)));

            return responseBody;
        });
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
}
