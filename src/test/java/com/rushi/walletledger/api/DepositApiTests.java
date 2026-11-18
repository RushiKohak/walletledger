package com.rushi.walletledger.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deposit behaviour over real HTTP against real PostgreSQL: the balance update, the balanced double
 * entry behind it, the treasury per currency, the rejected cases and concurrency.
 *
 * <p>Each test uses its own currency and its own account, and asserts relationships (the treasury
 * mirrors the users) rather than absolute totals, so tests do not depend on execution order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DepositApiTests extends ApiIntegrationTest {

    @Test
    void depositCreditsTheAccountAndReportsTheBalanceAfter() {
        long accountId = createUserAccount("CHF");

        ApiResponse first = deposit(accountId, 1000, "CHF");

        assertThat(first.status()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(first.textField("transactionId")).isNotBlank();
        assertThat(first.longField("accountId")).isEqualTo(accountId);
        assertThat(first.longField("amount")).isEqualTo(1000);
        assertThat(first.textField("currency")).isEqualTo("CHF");
        assertThat(first.longField("balanceAfter")).isEqualTo(1000);

        ApiResponse second = deposit(accountId, 250, "CHF");
        assertThat(second.longField("balanceAfter")).isEqualTo(1250);
        assertThat(balanceOf(accountId)).isEqualTo(1250);
    }

    @Test
    void depositWritesOneDebitAndOneCreditOfEqualAmountAndTheTreasuryMirrorsTheUsers() {
        long accountId = createUserAccount("JPY");

        ApiResponse response = deposit(accountId, 250, "JPY");
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED.value());
        String transactionId = response.textField("transactionId");

        List<Map<String, Object>> entries = jdbc.queryForList(
                "SELECT direction, account_id, amount FROM ledger_entries WHERE transaction_id = ?::uuid",
                transactionId);
        assertThat(entries).hasSize(2);

        List<String> directions = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        long debitedAccountId = 0L;
        for (Map<String, Object> entry : entries) {
            String direction = (String) entry.get("direction");
            directions.add(direction);
            amounts.add(((Number) entry.get("amount")).longValue());
            if ("DEBIT".equals(direction)) {
                debitedAccountId = ((Number) entry.get("account_id")).longValue();
            }
        }
        assertThat(directions).containsExactlyInAnyOrder("DEBIT", "CREDIT");
        assertThat(amounts).hasSize(2).containsOnly(250L);

        // The debit is the treasury, the credit is the user: debits equal credits, and the money
        // did not appear from nowhere.
        assertThat(debitedAccountId).isEqualTo(treasuryId("JPY"));
        assertThat(treasuryBalance("JPY")).isEqualTo(-250L);
        assertThat(sumOfUserBalances("JPY") + treasuryBalance("JPY")).isZero();

        Map<String, Object> transactionRow = jdbc.queryForMap(
                "SELECT type, status FROM ledger_transactions WHERE id = ?::uuid", transactionId);
        assertThat(transactionRow.get("type")).isEqualTo("DEPOSIT");
        assertThat(transactionRow.get("status")).isEqualTo("POSTED");
    }

    @Test
    void eachCurrencyGetsItsOwnTreasuryAndRepeatedDepositsReuseTheSameOne() {
        long sekAccount = createUserAccount("SEK");
        long nokAccount = createUserAccount("NOK");

        deposit(sekAccount, 100, "SEK");
        deposit(sekAccount, 200, "SEK");
        deposit(nokAccount, 300, "NOK");

        assertThat(treasuryCount("SEK")).isEqualTo(1L);
        assertThat(treasuryCount("NOK")).isEqualTo(1L);
        assertThat(treasuryBalance("SEK")).isEqualTo(-300L);
        assertThat(treasuryBalance("NOK")).isEqualTo(-300L);
    }

    @ParameterizedTest
    @MethodSource("nonPositiveAmounts")
    void rejectsANonPositiveOrMissingAmount(Long amount) {
        long accountId = createUserAccount("AUD");
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", accountId);
        body.put("amount", amount);
        body.put("currency", "AUD");

        assertError(postJson("/api/v1/deposits", body, UUID.randomUUID().toString()), HttpStatus.BAD_REQUEST);
        assertThat(balanceOf(accountId)).isZero();
    }

    static Stream<Long> nonPositiveAmounts() {
        return Stream.of(0L, -1L, -1000L, null);
    }

    @Test
    void rejectsACurrencyThatIsNotTheAccountCurrency() {
        long accountId = createUserAccount("AUD");

        ApiResponse response = postJson("/api/v1/deposits",
                Map.of("accountId", accountId, "amount", 100, "currency", "USD"),
                UUID.randomUUID().toString());

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("currency");
        assertThat(balanceOf(accountId)).isZero();
    }

    @Test
    void rejectsAMissingIdempotencyKey() {
        long accountId = createUserAccount("AUD");

        ApiResponse response = postJson("/api/v1/deposits",
                Map.of("accountId", accountId, "amount", 100, "currency", "AUD"));

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("Idempotency-Key");
        assertThat(balanceOf(accountId)).isZero();
    }

    @Test
    void rejectsADepositIntoASystemAccount() {
        long accountId = createUserAccount("ZAR");
        deposit(accountId, 100, "ZAR");
        long treasury = treasuryId("ZAR");

        ApiResponse response = postJson("/api/v1/deposits",
                Map.of("accountId", treasury, "amount", 100, "currency", "ZAR"),
                UUID.randomUUID().toString());

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("SYSTEM");
        assertThat(treasuryBalance("ZAR")).isEqualTo(-100L);
    }

    @Test
    void returns404ForADepositIntoAnUnknownAccount() {
        ApiResponse response = postJson("/api/v1/deposits",
                Map.of("accountId", 987654322, "amount", 100, "currency", "INR"),
                UUID.randomUUID().toString());

        assertError(response, HttpStatus.NOT_FOUND);
    }

    @Test
    void storesTheIdempotencyKeyAndAHashOfTheRequest() {
        long accountId = createUserAccount("CAD");
        String key = "deposit-" + UUID.randomUUID();

        ApiResponse response = postJson("/api/v1/deposits",
                Map.of("accountId", accountId, "amount", 500, "currency", "CAD"), key);
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED.value());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT idempotency_key, request_hash FROM ledger_transactions WHERE id = ?::uuid",
                response.textField("transactionId"));
        assertThat(row.get("idempotency_key")).isEqualTo(key);
        assertThat((String) row.get("request_hash")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void appliesEveryConcurrentDepositExactlyOnce() throws Exception {
        long accountId = createUserAccount("GBP");
        int concurrentDeposits = 50;
        long amount = 100L;

        ExecutorService pool = Executors.newFixedThreadPool(concurrentDeposits);
        try {
            List<Callable<ApiResponse>> requests = new ArrayList<>();
            for (int i = 0; i < concurrentDeposits; i++) {
                requests.add(() -> postJson("/api/v1/deposits",
                        Map.of("accountId", accountId, "amount", amount, "currency", "GBP"),
                        UUID.randomUUID().toString()));
            }
            // Every request uses its own idempotency key, so each one is a deposit that must
            // apply; the row locks are what keep them from losing each other's updates.
            for (Future<ApiResponse> result : pool.invokeAll(requests)) {
                assertThat(result.get().status()).isEqualTo(HttpStatus.CREATED.value());
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(balanceOf(accountId)).isEqualTo(concurrentDeposits * amount);
        long creditEntries = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE account_id = ? AND direction = 'CREDIT'",
                (rs, rowNum) -> rs.getLong(1), accountId);
        assertThat(creditEntries).isEqualTo((long) concurrentDeposits);
        assertThat(sumOfUserBalances("GBP") + treasuryBalance("GBP")).isZero();
    }

    private long treasuryCount(String currency) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE type = 'SYSTEM' AND currency = ?",
                (rs, rowNum) -> rs.getLong(1), currency);
    }
}
