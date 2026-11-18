package com.rushi.walletledger.api;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ledger invariants: the properties that must hold no matter how many requests raced.
 *
 * <p>They are checked in one place so every concurrency test asserts the same thing, and they are
 * scoped to the currencies a test created. The database is shared by every test class in the JVM,
 * and some tests deliberately seed states the API cannot reach, so a global sweep would fail for
 * reasons unrelated to the test that is running.
 */
final class LedgerInvariants {

    private final JdbcTemplate jdbc;

    LedgerInvariants(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs (a)-(d) over every account and transaction that touches one of these currencies. */
    void assertHold(String... currencies) {
        List<String> scope = List.of(currencies);
        assertNoNegativeUserBalances(scope);
        assertCurrencyTotalsAreZero(scope);
        assertCachedBalancesMatchEntries(scope);
        assertTransactionsAreBalanced(scope);
    }

    /**
     * (e) The number of 201 responses must equal the number of transaction rows the test created.
     * Because the currency is used by this test only, counting transactions in that currency is
     * counting the rows created in the test.
     */
    void assertCreatedTransactionsMatch(long createdResponses, String currency) {
        assertThat(transactionCount(currency))
                .as("ledger_transactions rows created in %s", currency)
                .isEqualTo(createdResponses);
    }

    /** Transactions touching a currency, optionally narrowed to one type. */
    long transactionCount(String currency) {
        return countTransactions(currency, null);
    }

    long transactionCount(String currency, String type) {
        return countTransactions(currency, type);
    }

    /** Every entry of every transfer in a currency; with two entries per transfer this is 2x. */
    long entriesInTransfers(String currency) {
        return jdbc.queryForObject("""
                SELECT count(e.id)
                FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.transaction_id
                JOIN accounts a ON a.id = e.account_id
                WHERE t.type = 'TRANSFER' AND a.currency = ?
                """, Long.class, currency);
    }

    /** (a) A user wallet can never owe money: the balance check constraint and the locks agree. */
    private void assertNoNegativeUserBalances(List<String> currencies) {
        Long negative = jdbc.queryForObject("""
                        SELECT count(*)
                        FROM accounts
                        WHERE type = 'USER' AND balance < 0 AND currency IN (%s)
                        """.formatted(placeholders(currencies)),
                Long.class, currencies.toArray());
        assertThat(negative).as("USER accounts with a negative balance in %s", currencies).isZero();
    }

    /**
     * (b) Money is conserved per currency. Every credit in a user wallet comes from a debit
     * somewhere, so the user balances and the treasury of that currency sum to zero.
     */
    private void assertCurrencyTotalsAreZero(List<String> currencies) {
        List<Long> totals = jdbc.queryForList("""
                        SELECT sum(balance)
                        FROM accounts
                        WHERE currency IN (%s)
                        GROUP BY currency
                        """.formatted(placeholders(currencies)),
                Long.class, currencies.toArray());
        assertThat(totals)
                .as("user balances plus treasury balance, per currency")
                .hasSize(currencies.size())
                .containsOnly(0L);
    }

    /**
     * (c) The cached balance is only a cache: it must equal the ledger it summarises, otherwise a
     * concurrent update was lost and the entries and the accounts disagree.
     */
    private void assertCachedBalancesMatchEntries(List<String> currencies) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT a.id AS account_id,
                               a.balance AS cached_balance,
                               coalesce(sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount
                                                 ELSE -e.amount END), 0) AS ledger_balance
                        FROM accounts a
                        LEFT JOIN ledger_entries e ON e.account_id = a.id
                        WHERE a.currency IN (%s)
                        GROUP BY a.id, a.balance
                        """.formatted(placeholders(currencies)),
                currencies.toArray());
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            assertThat(((Number) row.get("cached_balance")).longValue())
                    .as("cached balance of account %s equals its ledger entries", row.get("account_id"))
                    .isEqualTo(((Number) row.get("ledger_balance")).longValue());
        }
    }

    /**
     * (d) Every transaction is balanced, and a transfer is exactly two entries: one debit and one
     * credit for the same amount. A half-written transfer would violate both.
     */
    private void assertTransactionsAreBalanced(List<String> currencies) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT t.id AS transaction_id,
                               t.type AS type,
                               count(e.id) AS entries,
                               sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END) AS debits,
                               sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END) AS credits
                        FROM ledger_transactions t
                        JOIN ledger_entries e ON e.transaction_id = t.id
                        WHERE t.id IN (
                            SELECT e2.transaction_id
                            FROM ledger_entries e2
                            JOIN accounts a2 ON a2.id = e2.account_id
                            WHERE a2.currency IN (%s)
                        )
                        GROUP BY t.id, t.type
                        """.formatted(placeholders(currencies)),
                currencies.toArray());
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            long debits = ((Number) row.get("debits")).longValue();
            long credits = ((Number) row.get("credits")).longValue();
            assertThat(debits)
                    .as("debits of transaction %s equal its credits", row.get("transaction_id"))
                    .isEqualTo(credits);
            if ("TRANSFER".equals(row.get("type"))) {
                assertThat(((Number) row.get("entries")).longValue())
                        .as("entries of transfer %s", row.get("transaction_id"))
                        .isEqualTo(2L);
            }
        }
    }

    private long countTransactions(String currency, String type) {
        String sql = """
                SELECT count(DISTINCT t.id)
                FROM ledger_transactions t
                JOIN ledger_entries e ON e.transaction_id = t.id
                JOIN accounts a ON a.id = e.account_id
                WHERE a.currency = ?
                """
                + (type == null ? "" : " AND t.type = ?");
        return type == null
                ? jdbc.queryForObject(sql, Long.class, currency)
                : jdbc.queryForObject(sql, Long.class, currency, type);
    }

    private static String placeholders(List<String> values) {
        return values.stream().map(value -> "?").collect(Collectors.joining(", "));
    }
}
