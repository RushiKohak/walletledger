package com.rushi.walletledger.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency over real HTTP against real PostgreSQL: one key is one operation.
 *
 * <p>These tests exercise the three answers a key can produce (201 the first time, 200 with the
 * stored body on a retry, 422 when the key is reused for something else) and the two properties
 * that make the design worth having: a retry never moves money twice, even when it happens
 * concurrently, and a rejected request does not consume its key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyApiTests extends ApiIntegrationTest {

    @Test
    void depositReplayReturnsTheOriginalBodyWithoutMovingMoneyAgain() {
        long accountId = createUserAccount("JOD");
        String key = "deposit-" + UUID.randomUUID();

        ApiResponse first = sendDeposit(accountId, 400, "JOD", key);
        assertThat(first.status()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(first.header("Idempotent-Replayed")).isNull();
        long entriesAfterFirst = entryCount(accountId);

        ApiResponse replay = sendDeposit(accountId, 400, "JOD", key);

        assertThat(replay.status()).isEqualTo(HttpStatus.OK.value());
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(balanceOf(accountId)).isEqualTo(400);
        assertThat(entryCount(accountId)).isEqualTo(entriesAfterFirst);
        assertThat(countTransactions(key)).isEqualTo(1);
    }

    @Test
    void transferReplayReturnsTheOriginalBodyEvenAfterTheSenderIsDrained() {
        long sender = createUserAccount("QAR");
        long receiver = createUserAccount("QAR");
        deposit(sender, 500, "QAR");
        String key = "transfer-" + UUID.randomUUID();

        ApiResponse first = sendTransfer(sender, receiver, 500, "QAR", key);
        assertThat(first.status()).isEqualTo(HttpStatus.CREATED.value());

        // The sender is now empty, so this request evaluated again would be a 409. The replay must
        // be answered from the stored response, before any balance is looked at.
        assertThat(balanceOf(sender)).isZero();

        ApiResponse replay = sendTransfer(sender, receiver, 500, "QAR", key);

        assertThat(replay.status()).isEqualTo(HttpStatus.OK.value());
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(balanceOf(sender)).isZero();
        assertThat(balanceOf(receiver)).isEqualTo(500);
        assertThat(countTransactions(key)).isEqualTo(1);
        assertThat(entryCount(receiver)).isEqualTo(1);
    }

    @Test
    void reusingADepositKeyForADifferentDepositIsAConflict() {
        long accountId = createUserAccount("SAR");
        String key = "deposit-" + UUID.randomUUID();

        assertThat(sendDeposit(accountId, 100, "SAR", key).status())
                .isEqualTo(HttpStatus.CREATED.value());

        ApiResponse conflict = sendDeposit(accountId, 200, "SAR", key);

        assertError(conflict, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(conflict.textField("message")).contains("different request");
        assertThat(balanceOf(accountId)).isEqualTo(100);
        assertThat(countTransactions(key)).isEqualTo(1);
    }

    @Test
    void reusingATransferKeyForADifferentTransferIsAConflict() {
        long sender = createUserAccount("AED");
        long receiver = createUserAccount("AED");
        deposit(sender, 1000, "AED");
        String key = "transfer-" + UUID.randomUUID();

        assertThat(sendTransfer(sender, receiver, 100, "AED", key).status())
                .isEqualTo(HttpStatus.CREATED.value());

        ApiResponse conflict = sendTransfer(sender, receiver, 200, "AED", key);

        assertError(conflict, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(conflict.textField("message")).contains("different request");
        assertThat(balanceOf(sender)).isEqualTo(900);
        assertThat(balanceOf(receiver)).isEqualTo(100);
    }

    @Test
    void reusingADepositKeyForATransferIsAConflict() {
        long sender = createUserAccount("BDT");
        long receiver = createUserAccount("BDT");
        String key = "shared-" + UUID.randomUUID();
        assertThat(sendDeposit(sender, 100, "BDT", key).status())
                .isEqualTo(HttpStatus.CREATED.value());

        ApiResponse conflict = sendTransfer(sender, receiver, 50, "BDT", key);

        assertError(conflict, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(balanceOf(sender)).isEqualTo(100);
        assertThat(balanceOf(receiver)).isZero();
    }

    @Test
    void aRejectedRequestDoesNotConsumeTheKey() {
        long sender = createUserAccount("PKR");
        long receiver = createUserAccount("PKR");
        String key = "transfer-" + UUID.randomUUID();

        // No money yet: the transfer is rejected with 409 and stores nothing.
        ApiResponse rejected = sendTransfer(sender, receiver, 100, "PKR", key);
        assertError(rejected, HttpStatus.CONFLICT);
        assertThat(countTransactions(key)).isZero();

        // The same key is still free, so the same request succeeds once the account is funded.
        deposit(sender, 100, "PKR");
        ApiResponse accepted = sendTransfer(sender, receiver, 100, "PKR", key);

        assertThat(accepted.status()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(accepted.header("Idempotent-Replayed")).isNull();
        assertThat(balanceOf(sender)).isZero();
        assertThat(balanceOf(receiver)).isEqualTo(100);
        assertThat(countTransactions(key)).isEqualTo(1);
    }

    @Test
    void differentKeysAreIndependent() {
        long accountId = createUserAccount("PHP");

        ApiResponse first = sendDeposit(accountId, 100, "PHP", "deposit-" + UUID.randomUUID());
        ApiResponse second = sendDeposit(accountId, 150, "PHP", "deposit-" + UUID.randomUUID());

        assertThat(first.status()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(second.status()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(first.textField("transactionId")).isNotEqualTo(second.textField("transactionId"));
        assertThat(balanceOf(accountId)).isEqualTo(250);
        assertThat(entryCount(accountId)).isEqualTo(2);
    }

    @Test
    void twentyConcurrentDepositsWithTheSameKeyCreditTheMoneyOnce() throws Exception {
        long accountId = createUserAccount("IDR");
        String key = "deposit-" + UUID.randomUUID();
        int attempts = 20;

        List<ApiResponse> responses = concurrently(attempts, () -> sendDeposit(accountId, 250, "IDR", key));

        assertThat(responses).filteredOn(response -> response.status() == HttpStatus.CREATED.value())
                .hasSize(1);
        assertThat(responses).filteredOn(response -> response.status() == HttpStatus.OK.value())
                .hasSize(attempts - 1)
                .allSatisfy(response -> assertThat(response.header("Idempotent-Replayed")).isEqualTo("true"));
        assertThat(responses).extracting(response -> response.textField("transactionId"))
                .containsOnly(responses.get(0).textField("transactionId"));

        assertThat(balanceOf(accountId)).isEqualTo(250);
        assertThat(countTransactions(key)).isEqualTo(1);
        assertThat(entryCount(accountId)).isEqualTo(1);
    }

    @Test
    void twentyConcurrentTransfersWithTheSameKeyMoveTheMoneyOnce() throws Exception {
        long sender = createUserAccount("MYR");
        long receiver = createUserAccount("MYR");
        deposit(sender, 1000, "MYR");
        String key = "transfer-" + UUID.randomUUID();
        int attempts = 20;

        List<ApiResponse> responses = concurrently(attempts, () -> sendTransfer(sender, receiver, 200, "MYR", key));

        assertThat(responses).filteredOn(response -> response.status() == HttpStatus.CREATED.value())
                .hasSize(1);
        assertThat(responses).filteredOn(response -> response.status() == HttpStatus.OK.value())
                .hasSize(attempts - 1);

        assertThat(balanceOf(sender)).isEqualTo(800);
        assertThat(balanceOf(receiver)).isEqualTo(200);
        assertThat(countTransactions(key)).isEqualTo(1);
        assertThat(entryCount(receiver)).isEqualTo(1);
    }

    @Test
    void rejectsABlankIdempotencyKey() {
        long accountId = createUserAccount("TWD");
        long receiver = createUserAccount("TWD");

        assertError(sendDeposit(accountId, 100, "TWD", ""), HttpStatus.BAD_REQUEST);
        assertError(sendTransfer(accountId, receiver, 100, "TWD", ""), HttpStatus.BAD_REQUEST);

        assertThat(balanceOf(accountId)).isZero();
        assertThat(entryCount(accountId)).isZero();
    }

    @Test
    void rejectsAnIdempotencyKeyLongerThanTheColumn() {
        long accountId = createUserAccount("UAH");
        long receiver = createUserAccount("UAH");
        String key = "k".repeat(256);

        assertError(sendDeposit(accountId, 100, "UAH", key), HttpStatus.BAD_REQUEST);
        assertError(sendTransfer(accountId, receiver, 100, "UAH", key), HttpStatus.BAD_REQUEST);

        assertThat(balanceOf(accountId)).isZero();
        assertThat(entryCount(accountId)).isZero();
    }

    private ApiResponse sendDeposit(long accountId, long amount, String currency, String key) {
        return postJson("/api/v1/deposits",
                Map.of("accountId", accountId, "amount", amount, "currency", currency), key);
    }

    private ApiResponse sendTransfer(long fromAccountId, long toAccountId, long amount,
                                     String currency, String key) {
        return postJson("/api/v1/transfers", Map.of(
                "fromAccountId", fromAccountId,
                "toAccountId", toAccountId,
                "amount", amount,
                "currency", currency), key);
    }

    /** Runs the same request on {@code count} threads at once and collects every response. */
    private List<ApiResponse> concurrently(int count, Callable<ApiResponse> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Callable<ApiResponse>> calls = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                calls.add(request);
            }
            List<ApiResponse> responses = new ArrayList<>();
            for (Future<ApiResponse> future : pool.invokeAll(calls)) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long countTransactions(String idempotencyKey) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE idempotency_key = ?",
                (rs, rowNum) -> rs.getLong(1), idempotencyKey);
    }

    private long entryCount(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE account_id = ?",
                (rs, rowNum) -> rs.getLong(1), accountId);
    }
}
