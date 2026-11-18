package com.rushi.walletledger.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency over real HTTP against real PostgreSQL: many requests, one database.
 *
 * <p>Every test fires its requests through one latch so they really do overlap, and every test is
 * capped by {@link Timeout} so a deadlock fails the run instead of hanging it. After the requests,
 * {@link LedgerInvariants} checks the properties that no amount of interleaving may break: no
 * negative wallets, money conserved per currency, balances equal to their ledger, balanced
 * transactions, and one transaction row per successful request.
 *
 * <p>Each test uses its own currency, so the invariants can be scoped to exactly the data the test
 * created in the shared container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrencyApiTests extends ApiIntegrationTest {

    private static final int CREATED = HttpStatus.CREATED.value();
    private static final int REPLAYED = HttpStatus.OK.value();
    private static final int INSUFFICIENT_FUNDS = HttpStatus.CONFLICT.value();

    @Test
    @Timeout(30)
    void oneHundredParallelTransfersOfOneDrainAFundedAccountExactly() throws Exception {
        long from = createUserAccount("MOP");
        long to = createUserAccount("MOP");
        deposit(from, 100, "MOP");

        List<Callable<ApiResponse>> requests = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            requests.add(() -> transfer(from, to, 1, "MOP"));
        }

        List<ApiResponse> responses = runConcurrently(100, requests);

        assertThat(responses).allSatisfy(response -> assertThat(response.status()).isEqualTo(CREATED));
        assertThat(balanceOf(from)).isZero();
        assertThat(balanceOf(to)).isEqualTo(100);

        LedgerInvariants invariants = new LedgerInvariants(jdbc);
        invariants.assertHold("MOP");
        // 100 transfers plus the funding deposit, each with exactly one transaction row.
        invariants.assertCreatedTransactionsMatch(101, "MOP");
    }

    @Test
    @Timeout(30)
    void oversubscribedAccountAcceptsExactlyWhatItCanAfford() throws Exception {
        long from = createUserAccount("GHS");
        long to = createUserAccount("GHS");
        deposit(from, 60, "GHS");

        List<Callable<ApiResponse>> requests = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            requests.add(() -> transfer(from, to, 1, "GHS"));
        }

        List<ApiResponse> responses = runConcurrently(100, requests);

        assertThat(responses).allSatisfy(
                response -> assertThat(response.status()).isIn(CREATED, INSUFFICIENT_FUNDS));
        assertThat(responses).filteredOn(response -> response.status() == CREATED).hasSize(60);
        assertThat(responses).filteredOn(response -> response.status() == INSUFFICIENT_FUNDS).hasSize(40);

        assertThat(balanceOf(from)).isZero();
        assertThat(balanceOf(to)).isEqualTo(60);

        LedgerInvariants invariants = new LedgerInvariants(jdbc);
        invariants.assertHold("GHS");
        invariants.assertCreatedTransactionsMatch(61, "GHS");
    }

    @Test
    @Timeout(30)
    void transfersInOppositeDirectionsDoNotDeadlockAndEndWhereTheyStarted() throws Exception {
        long a = createUserAccount("TZS");
        long b = createUserAccount("TZS");
        deposit(a, 100, "TZS");
        deposit(b, 100, "TZS");

        List<Callable<ApiResponse>> requests = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            requests.add(() -> transfer(a, b, 1, "TZS"));
            requests.add(() -> transfer(b, a, 1, "TZS"));
        }

        // Both directions take the same two row locks in ascending id order, so one waits for the
        // other instead of each holding what the other needs. The @Timeout is the assertion that
        // no deadlock happened.
        List<ApiResponse> responses = runConcurrently(100, requests);

        assertThat(responses).allSatisfy(response -> assertThat(response.status()).isEqualTo(CREATED));
        assertThat(balanceOf(a)).isEqualTo(100);
        assertThat(balanceOf(b)).isEqualTo(100);

        LedgerInvariants invariants = new LedgerInvariants(jdbc);
        invariants.assertHold("TZS");
        invariants.assertCreatedTransactionsMatch(102, "TZS");
    }

    @Test
    @Timeout(30)
    void mixedTrafficAcrossManyAccountsKeepsEveryInvariant() throws Exception {
        List<Long> accounts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            accounts.add(createUserAccount("UGX"));
        }
        for (long account : accounts) {
            deposit(account, 1000, "UGX");
        }

        // Fixed seed, and every random choice is made here, on one thread, so the workload is
        // reproducible and no request is held back by a shared Random.
        Random random = new Random(2024);
        List<Callable<ApiResponse>> requests = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            long from = accounts.get(random.nextInt(accounts.size()));
            long candidate = accounts.get(random.nextInt(accounts.size()));
            while (candidate == from) {
                candidate = accounts.get(random.nextInt(accounts.size()));
            }
            long to = candidate;
            long amount = 1 + random.nextInt(50);
            requests.add(() -> transfer(from, to, amount, "UGX"));
        }
        for (int i = 0; i < 50; i++) {
            long account = accounts.get(random.nextInt(accounts.size()));
            long amount = 1 + random.nextInt(50);
            requests.add(() -> deposit(account, amount, "UGX"));
        }

        List<ApiResponse> responses = runConcurrently(32, requests);

        // A transfer can be refused for lack of funds; nothing else may fail, and never with a 500.
        assertThat(responses).allSatisfy(
                response -> assertThat(response.status()).isIn(CREATED, INSUFFICIENT_FUNDS));

        LedgerInvariants invariants = new LedgerInvariants(jdbc);
        invariants.assertHold("UGX");
        long created = 10 + responses.stream().filter(response -> response.status() == CREATED).count();
        invariants.assertCreatedTransactionsMatch(created, "UGX");
    }

    @Test
    @Timeout(30)
    void concurrentRetriesOfTheSameRequestMoveTheMoneyOnce() throws Exception {
        long from = createUserAccount("ZMW");
        long to = createUserAccount("ZMW");
        deposit(from, 1000, "ZMW");

        List<Callable<ApiResponse>> requests = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String key = "retry-" + UUID.randomUUID();
            // The same key and the same body, sent twice at once: one attempt does the transfer,
            // the other must replay it.
            requests.add(() -> transfer(from, to, 5, "ZMW", key));
            requests.add(() -> transfer(from, to, 5, "ZMW", key));
        }

        List<ApiResponse> responses = runConcurrently(32, requests);

        assertThat(responses).allSatisfy(
                response -> assertThat(response.status()).isIn(CREATED, REPLAYED));
        assertThat(responses).filteredOn(response -> response.status() == CREATED).hasSize(100);
        assertThat(responses).filteredOn(response -> response.status() == REPLAYED).hasSize(100);

        assertThat(balanceOf(from)).isEqualTo(500);
        assertThat(balanceOf(to)).isEqualTo(500);

        LedgerInvariants invariants = new LedgerInvariants(jdbc);
        invariants.assertHold("ZMW");
        assertThat(invariants.transactionCount("ZMW", "TRANSFER")).isEqualTo(100);
        // 100 transfers of exactly two entries each: no retry added a second pair.
        assertThat(invariants.entriesInTransfers("ZMW")).isEqualTo(200);
        invariants.assertCreatedTransactionsMatch(101, "ZMW");
    }

    @Test
    @Timeout(30)
    void twoCurrenciesMoveIndependentlyInParallel() throws Exception {
        long usdA = createUserAccount("USD");
        long usdB = createUserAccount("USD");
        long eurA = createUserAccount("EUR");
        long eurB = createUserAccount("EUR");

        // Nothing is funded up front: the first deposits for both currencies race to create their
        // treasuries, and the transfers may legitimately lose that race and get a 409.
        List<Callable<ApiResponse>> deposits = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            deposits.add(() -> deposit(usdA, 10, "USD"));
            deposits.add(() -> deposit(eurA, 10, "EUR"));
        }
        List<Callable<ApiResponse>> transfers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            transfers.add(() -> transfer(usdA, usdB, 5, "USD"));
            transfers.add(() -> transfer(eurA, eurB, 5, "EUR"));
        }
        List<Callable<ApiResponse>> requests = new ArrayList<>(deposits);
        requests.addAll(transfers);

        List<ApiResponse> responses = runConcurrently(32, requests);
        List<ApiResponse> depositResponses = responses.subList(0, deposits.size());
        List<ApiResponse> transferResponses = responses.subList(deposits.size(), responses.size());

        assertThat(depositResponses).allSatisfy(
                response -> assertThat(response.status()).isEqualTo(CREATED));
        assertThat(transferResponses).allSatisfy(
                response -> assertThat(response.status()).isIn(CREATED, INSUFFICIENT_FUNDS));

        LedgerInvariants invariants = new LedgerInvariants(jdbc);
        invariants.assertHold("USD", "EUR");
        assertThat(treasuryCount("USD")).isEqualTo(1);
        assertThat(treasuryCount("EUR")).isEqualTo(1);

        long createdUsd = 30 + transferResponses.stream()
                .filter(response -> response.status() == CREATED)
                .filter(response -> "USD".equals(response.textField("currency")))
                .count();
        long createdEur = 30 + transferResponses.stream()
                .filter(response -> response.status() == CREATED)
                .filter(response -> "EUR".equals(response.textField("currency")))
                .count();
        invariants.assertCreatedTransactionsMatch(createdUsd, "USD");
        invariants.assertCreatedTransactionsMatch(createdEur, "EUR");
    }

    /**
     * Fires every request at once: all workers wait on one latch, which is released as soon as the
     * last one is submitted, and the pool is fixed so the server sees a bounded load. Results come
     * back in submission order, so a test can tell which response belongs to which request.
     */
    private List<ApiResponse> runConcurrently(int threads, List<Callable<ApiResponse>> requests)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (Callable<ApiResponse> request : requests) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();

            List<ApiResponse> responses = new ArrayList<>();
            for (Future<ApiResponse> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private ApiResponse transfer(long fromAccountId, long toAccountId, long amount, String currency) {
        return transfer(fromAccountId, toAccountId, amount, currency, UUID.randomUUID().toString());
    }

    private ApiResponse transfer(long fromAccountId, long toAccountId, long amount,
                                 String currency, String idempotencyKey) {
        return postJson("/api/v1/transfers", Map.of(
                "fromAccountId", fromAccountId,
                "toAccountId", toAccountId,
                "amount", amount,
                "currency", currency), idempotencyKey);
    }

    private long treasuryCount(String currency) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE type = 'SYSTEM' AND currency = ?",
                Long.class, currency);
    }
}
