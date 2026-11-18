package com.rushi.walletledger.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transfer behaviour over real HTTP against real PostgreSQL: the double entry and both balances,
 * the rejected cases, and the promise that a rejected transfer leaves nothing behind.
 *
 * <p>Each test uses its own currency and its own accounts, and asserts relationships (the two
 * entries mirror each other, the money supply is unchanged) rather than absolute totals, so tests
 * do not depend on execution order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransferApiTests extends ApiIntegrationTest {

    private static final long UNKNOWN_ACCOUNT_ID = 987654321L;

    @Test
    void transferMovesMoneyAndWritesOneDebitAndOneCredit() {
        long sender = createUserAccount("PLN");
        long receiver = createUserAccount("PLN");
        deposit(sender, 1000, "PLN");

        ApiResponse response = transfer(sender, receiver, 400, "PLN");

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(response.textField("transactionId")).isNotBlank();
        assertThat(response.longField("fromAccountId")).isEqualTo(sender);
        assertThat(response.longField("toAccountId")).isEqualTo(receiver);
        assertThat(response.longField("amount")).isEqualTo(400);
        assertThat(response.textField("currency")).isEqualTo("PLN");
        assertThat(response.longField("fromBalanceAfter")).isEqualTo(600);
        assertThat(response.longField("toBalanceAfter")).isEqualTo(400);

        assertThat(balanceOf(sender)).isEqualTo(600);
        assertThat(balanceOf(receiver)).isEqualTo(400);

        String transactionId = response.textField("transactionId");
        List<Map<String, Object>> entries = jdbc.queryForList(
                "SELECT direction, account_id, amount FROM ledger_entries WHERE transaction_id = ?::uuid",
                transactionId);
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(entry -> entry.get("direction"))
                .containsExactlyInAnyOrder("DEBIT", "CREDIT");
        assertThat(entries).extracting(entry -> ((Number) entry.get("amount")).longValue())
                .containsOnly(400L);
        assertThat(entries)
                .filteredOn(entry -> "DEBIT".equals(entry.get("direction")))
                .singleElement()
                .satisfies(entry -> assertThat(((Number) entry.get("account_id")).longValue()).isEqualTo(sender));
        assertThat(entries)
                .filteredOn(entry -> "CREDIT".equals(entry.get("direction")))
                .singleElement()
                .satisfies(entry -> assertThat(((Number) entry.get("account_id")).longValue()).isEqualTo(receiver));

        Map<String, Object> transactionRow = jdbc.queryForMap(
                "SELECT type, status FROM ledger_transactions WHERE id = ?::uuid", transactionId);
        assertThat(transactionRow.get("type")).isEqualTo("TRANSFER");
        assertThat(transactionRow.get("status")).isEqualTo("POSTED");

        // A transfer creates no money: the users hold what they held before, and the treasury
        // side of the deposits is still exactly the negative of the user balances.
        assertThat(sumOfUserBalances("PLN") + treasuryBalance("PLN")).isZero();
    }

    @Test
    void insufficientFundsIsAConflictAndLeavesNoRowAndNoBalanceChange() {
        long sender = createUserAccount("HUF");
        long receiver = createUserAccount("HUF");
        deposit(sender, 100, "HUF");
        String key = "transfer-" + UUID.randomUUID();

        ApiResponse response = transfer(sender, receiver, 101, "HUF", key);

        assertError(response, HttpStatus.CONFLICT);
        assertThat(response.textField("message")).contains("insufficient");
        assertThat(balanceOf(sender)).isEqualTo(100);
        assertThat(balanceOf(receiver)).isZero();
        assertThat(countTransactionsWithKey(key)).isZero();
        assertThat(entryCount(receiver)).isZero();
        // The sender has only the deposit's credit entry: no debit was written for this transfer.
        assertThat(entryCount(sender)).isEqualTo(1);
    }

    @Test
    void aTransferOfExactlyTheFullBalanceSucceeds() {
        long sender = createUserAccount("CZK");
        long receiver = createUserAccount("CZK");
        deposit(sender, 500, "CZK");

        ApiResponse response = transfer(sender, receiver, 500, "CZK");

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(response.longField("fromBalanceAfter")).isZero();
        assertThat(response.longField("toBalanceAfter")).isEqualTo(500);
        assertThat(balanceOf(sender)).isZero();
        assertThat(balanceOf(receiver)).isEqualTo(500);
    }

    @Test
    void rejectsATransferToTheSameAccount() {
        long account = createUserAccount("RON");
        deposit(account, 100, "RON");

        ApiResponse response = transfer(account, account, 100, "RON");

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("different");
        assertThat(balanceOf(account)).isEqualTo(100);
    }

    @Test
    void rejectsACurrencyThatIsNotTheAccountCurrency() {
        long sender = createUserAccount("BGN");
        long receiver = createUserAccount("BGN");

        ApiResponse response = transfer(sender, receiver, 10, "EUR");

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("currency");
        assertThat(balanceOf(sender)).isZero();
        assertThat(balanceOf(receiver)).isZero();
    }

    @Test
    void rejectsTwoAccountsThatDoNotShareTheSameCurrency() {
        long sender = createUserAccount("ISK");
        long receiver = createUserAccount("NZD");

        // The request currency matches the sender, so the rejection comes from the receiver's
        // currency: the two accounts would otherwise move money across currencies at 1:1.
        ApiResponse response = transfer(sender, receiver, 10, "ISK");

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("currency");
        assertThat(balanceOf(sender)).isZero();
        assertThat(balanceOf(receiver)).isZero();
    }

    @Test
    void returns404NamingAnUnknownSender() {
        long receiver = createUserAccount("MXN");

        ApiResponse response = transfer(UNKNOWN_ACCOUNT_ID, receiver, 10, "MXN");

        assertError(response, HttpStatus.NOT_FOUND);
        assertThat(response.textField("message")).contains(String.valueOf(UNKNOWN_ACCOUNT_ID));
    }

    @Test
    void returns404NamingAnUnknownReceiver() {
        long sender = createUserAccount("BRL");

        ApiResponse response = transfer(sender, UNKNOWN_ACCOUNT_ID, 10, "BRL");

        assertError(response, HttpStatus.NOT_FOUND);
        assertThat(response.textField("message")).contains(String.valueOf(UNKNOWN_ACCOUNT_ID));
    }

    @Test
    void rejectsTheTreasuryAsSender() {
        long account = createUserAccount("SGD");
        deposit(account, 100, "SGD");
        long treasury = treasuryId("SGD");

        ApiResponse response = transfer(treasury, account, 10, "SGD");

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("SYSTEM");
        assertThat(treasuryBalance("SGD")).isEqualTo(-100L);
        assertThat(balanceOf(account)).isEqualTo(100);
    }

    @Test
    void rejectsTheTreasuryAsReceiver() {
        long account = createUserAccount("HKD");
        deposit(account, 100, "HKD");
        long treasury = treasuryId("HKD");

        ApiResponse response = transfer(account, treasury, 10, "HKD");

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("SYSTEM");
        assertThat(treasuryBalance("HKD")).isEqualTo(-100L);
        assertThat(balanceOf(account)).isEqualTo(100);
    }

    @Test
    void rejectsAMissingIdempotencyKey() {
        long sender = createUserAccount("KRW");
        long receiver = createUserAccount("KRW");

        ApiResponse response = postJson("/api/v1/transfers", Map.of(
                "fromAccountId", sender, "toAccountId", receiver, "amount", 10, "currency", "KRW"));

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("Idempotency-Key");
        assertThat(balanceOf(sender)).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidAmounts")
    void rejectsANonPositiveOrMissingAmount(Long amount) {
        long sender = createUserAccount("THB");
        long receiver = createUserAccount("THB");
        Map<String, Object> body = new HashMap<>();
        body.put("fromAccountId", sender);
        body.put("toAccountId", receiver);
        body.put("amount", amount);
        body.put("currency", "THB");

        assertError(postJson("/api/v1/transfers", body, UUID.randomUUID().toString()), HttpStatus.BAD_REQUEST);
        assertThat(balanceOf(sender)).isZero();
        assertThat(balanceOf(receiver)).isZero();
        assertThat(entryCount(sender)).isZero();
    }

    static Stream<Long> invalidAmounts() {
        return Stream.of(0L, -1L, -1000L, null);
    }

    @Test
    void rejectsATransferWhoseCreditWouldOverflowTheReceiverBalance() {
        long sender = createUserAccount("KWD");
        long receiver = createUserAccount("KWD");

        // The API alone cannot reach this state: every unit of a currency enters through a deposit
        // that debits the treasury, and that debit is itself checked, so the money in one currency
        // can never exceed Long.MAX_VALUE and a receiver can never sit this close to the ceiling
        // with a funded sender beside it. The balances are seeded directly to exercise the
        // Math.addExact guard in Account.credit, the same guard a deposit depends on.
        jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", Long.MAX_VALUE, receiver);
        jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", 5L, sender);

        ApiResponse response = transfer(sender, receiver, 5, "KWD");

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("range");
        assertThat(balanceOf(sender)).isEqualTo(5);
        assertThat(balanceOf(receiver)).isEqualTo(Long.MAX_VALUE);
        assertThat(transferTransactionCount("KWD")).isZero();
    }

    private ApiResponse transfer(long fromAccountId, long toAccountId, long amount, String currency) {
        return transfer(fromAccountId, toAccountId, amount, currency, UUID.randomUUID().toString());
    }

    private ApiResponse transfer(long fromAccountId, long toAccountId, long amount, String currency, String key) {
        return postJson("/api/v1/transfers", Map.of(
                "fromAccountId", fromAccountId,
                "toAccountId", toAccountId,
                "amount", amount,
                "currency", currency), key);
    }

    private long countTransactionsWithKey(String idempotencyKey) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE idempotency_key = ?",
                (rs, rowNum) -> rs.getLong(1), idempotencyKey);
    }

    /** Transfer transactions in a currency, found through the accounts their entries touch. */
    private long transferTransactionCount(String currency) {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT t.id)
                FROM ledger_transactions t
                JOIN ledger_entries e ON e.transaction_id = t.id
                JOIN accounts a ON a.id = e.account_id
                WHERE t.type = 'TRANSFER' AND a.currency = ?
                """, (rs, rowNum) -> rs.getLong(1), currency);
    }

    private long entryCount(long accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE account_id = ?",
                (rs, rowNum) -> rs.getLong(1), accountId);
    }
}
