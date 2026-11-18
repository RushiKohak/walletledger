package com.rushi.walletledger.service;

import com.rushi.walletledger.error.IdempotencyKeyConflictException;
import com.rushi.walletledger.error.InvalidRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Idempotency for every money-moving endpoint, in one place.
 *
 * <p>The contract is: one idempotency key is one operation. The first request with a key performs
 * the movement and stores its response with the transaction row; every later request with the same
 * key gets that stored response back instead of moving money again. A later request that reuses the
 * key for something else is rejected, because the key already means a different operation.
 *
 * <p>Two mechanisms make that hold under concurrency:
 * <ul>
 *   <li>{@code pg_advisory_xact_lock} on the key serialises requests that share it, so the second
 *       request waits for the first transaction to end and then sees its row. The lock is
 *       transaction-scoped, so committing or rolling back releases it with no cleanup code.</li>
 *   <li>The {@code UNIQUE} constraint on {@code idempotency_key} stays as a last-resort backstop.
 *       It is not something the code catches or depends on: catching a constraint violation inside
 *       the same transaction cannot work, because PostgreSQL aborts the whole transaction.</li>
 * </ul>
 *
 * <p>The advisory lock is taken before any account row is locked, so it never participates in a
 * cycle with the ascending-id account locks: the key lock is always acquired first and released
 * last.
 */
@Service
public class IdempotencyService {

    /** {@code ledger_transactions.idempotency_key} is VARCHAR(255). */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Runs {@code movement} at most once for this key and turns the outcome into a response.
     *
     * <p>The replay lookup happens before {@code movement} runs, so it is answered before any
     * business validation: a transfer that succeeded once still replays its original response
     * after the sender has spent the money elsewhere.
     *
     * @param movement work that performs the movement and returns the JSON body it also stored on
     *                 the transaction row; it is only called when the key is new
     */
    public MoneyMovementResult execute(String idempotencyKey,
                                       String requestHash,
                                       Function<String, String> movement) {
        String key = requireKey(idempotencyKey);

        lockKey(key);

        Optional<String> storedBody = storedResponseBody(key, requestHash);
        if (storedBody.isPresent()) {
            return new MoneyMovementResult(storedBody.get(), true);
        }
        return new MoneyMovementResult(movement.apply(key), false);
    }

    /** Serialises a response record exactly the way it will be stored and replayed. */
    public String json(Object response) {
        return JSON.writeValueAsString(response);
    }

    /**
     * Takes the transaction-scoped advisory lock for a key. Requests with the same key queue here
     * instead of racing to insert the same {@code idempotency_key}.
     */
    private void lockKey(String key) {
        // cast(? as text) because hashtextextended takes text, while the driver sends the
        // parameter as varchar.
        entityManager.createNativeQuery(
                        "select pg_advisory_xact_lock(hashtextextended(cast(?1 as text), 0))")
                .setParameter(1, key)
                .getSingleResult();
    }

    /**
     * The stored body for a key, or empty when the key has never completed a transaction.
     *
     * @throws IdempotencyKeyConflictException if the key exists but was used for a different request
     */
    private Optional<String> storedResponseBody(String key, String requestHash) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select request_hash, cast(response_body as text)
                        from ledger_transactions
                        where idempotency_key = ?1
                        """)
                .setParameter(1, key)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        String storedHash = (String) row[0];
        if (!storedHash.equals(requestHash)) {
            throw new IdempotencyKeyConflictException(key);
        }
        return Optional.of((String) row[1]);
    }

    private static String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidRequestException("the Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidRequestException(
                    "the Idempotency-Key header must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return idempotencyKey;
    }
}
