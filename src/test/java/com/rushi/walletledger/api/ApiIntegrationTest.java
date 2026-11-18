package com.rushi.walletledger.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared setup for the HTTP tests.
 *
 * <p>Every assertion goes over a real socket: the application runs on a random port against a real
 * PostgreSQL container, so the controller, the JSON mapping, the validation, the transaction
 * boundaries and the database constraints are all exercised, not just the service methods.
 *
 * <p>The client is the JDK's {@link HttpClient}, which is what keeps these tests honest: nothing
 * rewrites or short-circuits the request, and a 4xx/5xx response is returned as data instead of
 * being thrown, so the error bodies can be asserted directly.
 *
 * <p>The container is a singleton, started once for the JVM and removed by Testcontainers when the
 * JVM exits. Tests stay independent by using a different currency each rather than by deleting
 * rows: wiping a shared ledger would hide exactly the kind of bug these tests look for.
 */
abstract class ApiIntegrationTest {

    @SuppressWarnings("rawtypes")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** The port the application really bound to, published by the random-port test support. */
    @Value("${local.server.port}")
    private int port;

    @Autowired
    protected JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private final ObjectMapper json = JsonMapper.builder().build();

    /** A response as data: the status code and the parsed JSON body. */
    protected record ApiResponse(int status, JsonNode body) {

        long longField(String name) {
            assertThat(body).as("response body").isNotNull();
            return body.get(name).asLong();
        }

        String textField(String name) {
            assertThat(body).as("response body").isNotNull();
            return body.get(name).asText();
        }
    }

    protected ApiResponse postJson(String path, Map<String, ?> body) {
        return postJson(path, body, null);
    }

    protected ApiResponse postJson(String path, Map<String, ?> body, String idempotencyKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return send(request.build());
    }

    protected ApiResponse getJson(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    protected long createUserAccount(String currency) {
        ApiResponse response = postJson("/api/v1/accounts",
                Map.of("ownerName", "Owner of " + currency, "currency", currency));
        assertThat(response.status()).as("create account in %s", currency).isEqualTo(HttpStatus.CREATED.value());
        return response.longField("id");
    }

    /** A deposit with a fresh idempotency key, which is what a new request should always send. */
    protected ApiResponse deposit(long accountId, long amount, String currency) {
        return postJson("/api/v1/deposits",
                Map.of("accountId", accountId, "amount", amount, "currency", currency),
                UUID.randomUUID().toString());
    }

    protected long balanceOf(long accountId) {
        ApiResponse response = getJson("/api/v1/accounts/" + accountId);
        assertThat(response.status()).isEqualTo(HttpStatus.OK.value());
        return response.longField("balance");
    }

    protected long treasuryBalance(String currency) {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT balance FROM accounts WHERE type = 'SYSTEM' AND currency = ?), 0)
                """, (rs, rowNum) -> rs.getLong(1), currency);
    }

    protected long sumOfUserBalances(String currency) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE type = 'USER' AND currency = ?",
                (rs, rowNum) -> rs.getLong(1), currency);
    }

    protected long treasuryId(String currency) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE type = 'SYSTEM' AND currency = ?",
                (rs, rowNum) -> rs.getLong(1), currency);
    }

    /** Asserts the single error shape: status, error, message and timestamp. */
    protected void assertError(ApiResponse response, HttpStatus expected) {
        assertThat(response.status()).isEqualTo(expected.value());
        JsonNode body = response.body();
        assertThat(body).isNotNull();
        assertThat(body.get("status").asInt()).isEqualTo(expected.value());
        assertThat(body.get("error").asText()).isEqualTo(expected.getReasonPhrase());
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(body.get("timestamp").asText()).isNotBlank();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private ApiResponse send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new ApiResponse(response.statusCode(), json.readTree(response.body()));
        } catch (IOException exception) {
            throw new IllegalStateException("HTTP call to " + request.uri() + " failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP call to " + request.uri() + " was interrupted", exception);
        }
    }
}
