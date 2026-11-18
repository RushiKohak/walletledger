package com.rushi.walletledger.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/accounts/{id}/entries} over real HTTP against real PostgreSQL: the paging
 * contract, the ordering, and the rejections.
 *
 * <p>The account is funded through the deposit endpoint rather than by writing rows, so the
 * entries under test are the ones the application really produces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EntriesApiTests extends ApiIntegrationTest {

    @Test
    void returnsTheNewestEntryFirstWithTheDocumentedFields() {
        long accountId = createUserAccount("PEN");
        deposit(accountId, 100, "PEN");
        ApiResponse third = deposit(accountId, 300, "PEN");
        deposit(accountId, 200, "PEN");

        ApiResponse response = getJson("/api/v1/accounts/" + accountId + "/entries");

        assertThat(response.status()).isEqualTo(HttpStatus.OK.value());
        JsonNode content = response.body().get("content");
        assertThat(content).hasSize(3);

        // ids descend, so the entry from the most recent deposit is first.
        long first = content.get(0).get("id").asLong();
        long second = content.get(1).get("id").asLong();
        long last = content.get(2).get("id").asLong();
        assertThat(first).isGreaterThan(second).isGreaterThan(last);
        assertThat(content.get(0).get("amount").asLong()).isEqualTo(200);
        assertThat(content.get(2).get("amount").asLong()).isEqualTo(100);

        // Each deposit credits the wallet, and the entry points back at the transaction that wrote it.
        assertThat(content.get(0).get("direction").asText()).isEqualTo("CREDIT");
        assertThat(content.get(0).get("transactionId").asText()).isNotBlank();
        assertThat(content.get(0).get("createdAt").asText()).isNotBlank();
        assertThat(content.get(0).get("transactionId").asText())
                .isNotEqualTo(third.textField("transactionId"));
    }

    @Test
    void defaultsToTheFirstPageOfTwenty() {
        long accountId = createUserAccount("PEN");
        deposit(accountId, 100, "PEN");

        ApiResponse response = getJson("/api/v1/accounts/" + accountId + "/entries");

        assertThat(response.longField("page")).isZero();
        assertThat(response.longField("size")).isEqualTo(20);
        assertThat(response.longField("totalElements")).isEqualTo(1);
        assertThat(response.longField("totalPages")).isEqualTo(1);
    }

    @Test
    void reportsTheTotalsAcrossTheFirstAndLastPage() {
        long accountId = createUserAccount("PEN");
        deposit(accountId, 100, "PEN");
        deposit(accountId, 200, "PEN");
        deposit(accountId, 300, "PEN");

        ApiResponse firstPage = getJson("/api/v1/accounts/" + accountId + "/entries?page=0&size=2");
        assertThat(firstPage.status()).isEqualTo(HttpStatus.OK.value());
        assertThat(amounts(firstPage)).containsExactly(300L, 200L);
        assertThat(firstPage.longField("page")).isZero();
        assertThat(firstPage.longField("size")).isEqualTo(2);
        assertThat(firstPage.longField("totalElements")).isEqualTo(3);
        assertThat(firstPage.longField("totalPages")).isEqualTo(2);

        ApiResponse lastPage = getJson("/api/v1/accounts/" + accountId + "/entries?page=1&size=2");
        assertThat(lastPage.status()).isEqualTo(HttpStatus.OK.value());
        assertThat(amounts(lastPage)).containsExactly(100L);
        assertThat(lastPage.longField("page")).isEqualTo(1);
        assertThat(lastPage.longField("totalElements")).isEqualTo(3);
        assertThat(lastPage.longField("totalPages")).isEqualTo(2);
    }

    @Test
    void returnsAnEmptyPageForAnAccountThatHasNoEntries() {
        long accountId = createUserAccount("PEN");

        ApiResponse response = getJson("/api/v1/accounts/" + accountId + "/entries");

        assertThat(response.status()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.body().get("content")).isEmpty();
        assertThat(response.longField("totalElements")).isZero();
        assertThat(response.longField("totalPages")).isZero();
    }

    @Test
    void acceptsTheLargestAllowedPageSize() {
        long accountId = createUserAccount("PEN");
        deposit(accountId, 100, "PEN");
        deposit(accountId, 200, "PEN");

        ApiResponse response = getJson("/api/v1/accounts/" + accountId + "/entries?size=100");

        assertThat(response.status()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.longField("size")).isEqualTo(100);
        assertThat(amounts(response)).containsExactly(200L, 100L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?size=0", "?size=101", "?page=-1", "?page=0&size=-5"})
    void rejectsAPageOrSizeOutsideTheAllowedRange(String query) {
        long accountId = createUserAccount("PEN");

        ApiResponse response = getJson("/api/v1/accounts/" + accountId + "/entries" + query);

        assertError(response, HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=abc", "?size=abc", "?page=1.5"})
    void rejectsAPaginationParameterThatIsNotAWholeNumber(String query) {
        long accountId = createUserAccount("PEN");

        assertError(getJson("/api/v1/accounts/" + accountId + "/entries" + query),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void returns404ForTheEntriesOfAnUnknownAccount() {
        ApiResponse response = getJson("/api/v1/accounts/987654323/entries");

        assertError(response, HttpStatus.NOT_FOUND);
        assertThat(response.textField("message")).contains("987654323");
    }

    @Test
    void returns400ForANonNumericAccountIdInThePath() {
        assertError(getJson("/api/v1/accounts/not-a-number/entries"), HttpStatus.BAD_REQUEST);
        assertError(getJson("/api/v1/accounts/not-a-number"), HttpStatus.BAD_REQUEST);
    }

    private static List<Long> amounts(ApiResponse response) {
        List<Long> amounts = new ArrayList<>();
        for (JsonNode entry : response.body().get("content")) {
            amounts.add(entry.get("amount").asLong());
        }
        return amounts;
    }
}
