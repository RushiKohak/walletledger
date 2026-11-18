package com.rushi.walletledger.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Account creation and retrieval, over real HTTP against real PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountApiTests extends ApiIntegrationTest {

    @Test
    void createsAUserAccountWithZeroBalanceAndReturnsItById() {
        ApiResponse created = postJson("/api/v1/accounts",
                Map.of("ownerName", "Asha Verma", "currency", "INR"));

        assertThat(created.status()).isEqualTo(HttpStatus.CREATED.value());
        JsonNode body = created.body();
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(body.get("ownerName").asText()).isEqualTo("Asha Verma");
        assertThat(body.get("currency").asText()).isEqualTo("INR");
        assertThat(body.get("balance").asLong()).isZero();
        assertThat(body.get("type").asText()).isEqualTo("USER");

        ApiResponse fetched = getJson("/api/v1/accounts/" + body.get("id").asLong());
        assertThat(fetched.status()).isEqualTo(HttpStatus.OK.value());
        assertThat(fetched.body()).isEqualTo(body);
    }

    @Test
    void returnsTheErrorBodyForAnUnknownAccount() {
        ApiResponse response = getJson("/api/v1/accounts/987654321");

        assertError(response, HttpStatus.NOT_FOUND);
        assertThat(response.textField("message")).contains("987654321");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void rejectsABlankOwnerName(String ownerName) {
        assertError(postJson("/api/v1/accounts", accountBody(ownerName, "INR")), HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsAnOwnerNameLongerThanOneHundredCharacters() {
        assertError(postJson("/api/v1/accounts", accountBody("a".repeat(101), "INR")), HttpStatus.BAD_REQUEST);

        // The limit itself must still be accepted, so 100 characters is not rejected by mistake.
        ApiResponse atLimit = postJson("/api/v1/accounts", accountBody("a".repeat(100), "NPR"));
        assertThat(atLimit.status()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(atLimit.textField("ownerName")).hasSize(100);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"inr", "InR", "IN", "INRR", ""})
    void rejectsACurrencyThatIsNotThreeUppercaseLetters(String currency) {
        assertError(postJson("/api/v1/accounts", accountBody("Asha Verma", currency)), HttpStatus.BAD_REQUEST);
    }

    private static Map<String, Object> accountBody(String ownerName, String currency) {
        // HashMap, not Map.of, because these tests need to send explicit nulls.
        Map<String, Object> body = new HashMap<>();
        body.put("ownerName", ownerName);
        body.put("currency", currency);
        return body;
    }
}
