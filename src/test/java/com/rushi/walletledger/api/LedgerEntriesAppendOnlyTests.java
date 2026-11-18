package com.rushi.walletledger.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Proves the ledger is append-only in the database, not just by convention in Java.
 *
 * <p>These tests deliberately go around the application: they write SQL straight through
 * {@link org.springframework.jdbc.core.JdbcTemplate}, the same way a manual fix in psql or a future
 * buggy service would. If a migration ever drops the trigger, the repository interfaces stay
 * narrow and every other test stays green, but these two fail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerEntriesAppendOnlyTests extends ApiIntegrationTest {

    @Test
    void anUpdateOfALedgerEntryIsRejectedAndChangesNothing() {
        String transactionId = postedTransaction("TRY", 700);
        long entriesBefore = countEntries(transactionId);

        Throwable failure = catchThrowable(() -> jdbc.update(
                "UPDATE ledger_entries SET amount = amount + 1 WHERE transaction_id = ?::uuid",
                transactionId));

        assertThat(failure)
                .as("an UPDATE on ledger_entries must fail")
                .isInstanceOf(DataAccessException.class);
        assertThat(failure).rootCause().hasMessageContaining("append-only");

        // The statement was refused before the row was touched, so the entries are unchanged.
        assertThat(countEntries(transactionId)).isEqualTo(entriesBefore);
        assertThat(amountsOf(transactionId)).containsOnly(700L);
    }

    @Test
    void aDeleteOfALedgerEntryIsRejectedAndChangesNothing() {
        String transactionId = postedTransaction("RSD", 900);
        long entriesBefore = countEntries(transactionId);

        Throwable failure = catchThrowable(() -> jdbc.update(
                "DELETE FROM ledger_entries WHERE transaction_id = ?::uuid",
                transactionId));

        assertThat(failure)
                .as("a DELETE on ledger_entries must fail")
                .isInstanceOf(DataAccessException.class);
        assertThat(failure).rootCause().hasMessageContaining("append-only");

        assertThat(countEntries(transactionId)).isEqualTo(entriesBefore);
        assertThat(amountsOf(transactionId)).containsOnly(900L);
    }

    @Test
    void aTruncateOfTheLedgerEntriesTableIsRejectedAndChangesNothing() {
        String transactionId = postedTransaction("BAM", 300);

        Throwable failure = catchThrowable(() -> jdbc.execute("TRUNCATE ledger_entries"));

        assertThat(failure)
                .as("a TRUNCATE of ledger_entries must fail")
                .isInstanceOf(DataAccessException.class);
        assertThat(failure).rootCause().hasMessageContaining("append-only");

        // A statement-level trigger refuses the whole statement, so no row anywhere was lost.
        assertThat(totalEntries()).isGreaterThanOrEqualTo(2L);
        assertThat(countEntries(transactionId)).isEqualTo(2L);
        assertThat(amountsOf(transactionId)).containsOnly(300L);
    }

    /** Creates a real posted deposit and returns its transaction id, so both entries exist. */
    private String postedTransaction(String currency, long amount) {
        long accountId = createUserAccount(currency);
        ApiResponse response = deposit(accountId, amount, currency);
        assertThat(response.status()).isEqualTo(HttpStatus.CREATED.value());
        return response.textField("transactionId");
    }

    private long totalEntries() {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entries", (rs, rowNum) -> rs.getLong(1));
    }

    private long countEntries(String transactionId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE transaction_id = ?::uuid",
                (rs, rowNum) -> rs.getLong(1), transactionId);
    }

    private List<Long> amountsOf(String transactionId) {
        return jdbc.queryForList(
                "SELECT amount FROM ledger_entries WHERE transaction_id = ?::uuid",
                Long.class, transactionId);
    }
}
