package com.rushi.walletledger.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every error the API can produce comes back as the same JSON body: {@code status, error, message,
 * timestamp}, whatever layer rejected the request.
 *
 * <p>These are the cases a client can hit before any business rule runs: a bad path, a body that is
 * not JSON, a content type or method the endpoint does not accept, a route that does not exist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorHandlingApiTests extends ApiIntegrationTest {

    @Test
    void aNonNumericPathIdIsABadRequest() {
        ApiResponse response = getJson("/api/v1/accounts/not-a-number");

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("id");
    }

    @Test
    void aMalformedJsonBodyIsABadRequest() {
        ApiResponse response = sendRaw("POST", "/api/v1/accounts", "application/json",
                "{\"ownerName\": \"Asha\", \"currency\":");

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("malformed");
    }

    @Test
    void aMissingRequestBodyIsABadRequest() {
        ApiResponse response = sendRaw("POST", "/api/v1/accounts", "application/json", null);

        assertError(response, HttpStatus.BAD_REQUEST);
    }

    @Test
    void aMissingIdempotencyKeyIsABadRequest() {
        long accountId = createUserAccount("EGP");

        ApiResponse response = postJson("/api/v1/deposits",
                Map.of("accountId", accountId, "amount", 100, "currency", "EGP"));

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("Idempotency-Key");
        assertThat(balanceOf(accountId)).isZero();
    }

    @Test
    void anUnsupportedContentTypeIs415() {
        ApiResponse response = sendRaw("POST", "/api/v1/accounts", "text/plain",
                "ownerName=Asha&currency=INR");

        assertError(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.textField("message")).contains("application/json");
    }

    @Test
    void anUnsupportedMethodIs405() {
        ApiResponse response = sendRaw("DELETE", "/api/v1/accounts/1", null, null);

        assertError(response, HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.textField("message")).contains("DELETE");
    }

    @Test
    void anUnknownRouteIs404() {
        ApiResponse response = getJson("/api/v1/does-not-exist");

        assertError(response, HttpStatus.NOT_FOUND);
        assertThat(response.textField("message")).contains("path");
    }

    @Test
    void anUnknownRouteUnderAKnownPrefixIs404() {
        ApiResponse response = getJson("/api/v1/accounts/1/statements");

        assertError(response, HttpStatus.NOT_FOUND);
    }

    @Test
    void aValidationFailureUsesTheSameBody() {
        ApiResponse response = postJson("/api/v1/accounts",
                Map.of("ownerName", " ", "currency", "INR"));

        assertError(response, HttpStatus.BAD_REQUEST);
        assertThat(response.textField("message")).contains("ownerName");
    }

    @Test
    void anExistingErrorKeepsItsStatusAndShape() {
        // Guard against the new catch-all handler swallowing the errors that already worked.
        long sender = createUserAccount("EGP");
        long receiver = createUserAccount("EGP");
        deposit(sender, 100, "EGP");

        ApiResponse insufficient = postJson("/api/v1/transfers", Map.of(
                "fromAccountId", sender, "toAccountId", receiver,
                "amount", 500, "currency", "EGP"), UUID.randomUUID().toString());
        assertError(insufficient, HttpStatus.CONFLICT);

        String key = "deposit-" + UUID.randomUUID();
        assertThat(postJson("/api/v1/deposits",
                Map.of("accountId", sender, "amount", 100, "currency", "EGP"), key).status())
                .isEqualTo(HttpStatus.CREATED.value());
        ApiResponse reused = postJson("/api/v1/deposits",
                Map.of("accountId", sender, "amount", 200, "currency", "EGP"), key);
        assertError(reused, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
