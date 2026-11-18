package com.rushi.walletledger.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Translates exceptions into HTTP responses so controllers and services stay free of status codes.
 *
 * <p>This is intentionally small for now: exactly the mappings this step needs. Exceptions that
 * are not listed here still propagate to the container and become a 500, which is the right answer
 * for a bug, but they would not use the error shape above until a later step covers them.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * A request that is valid but conflicts with the current state: a transfer larger than the
     * sender's balance. 409 rather than 400 or 422, because the same request may succeed later.
     */
    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException exception) {
        return respond(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException exception) {
        return respond(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /** Bean Validation failures on a request body, for example a blank owner name. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        // Sorted so the message is deterministic when several fields fail at once.
        String message = exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return respond(HttpStatus.BAD_REQUEST,
                message.isEmpty() ? "request body is invalid" : message);
    }

    /** A body that is not valid JSON, or a number too large to fit the field it targets. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return respond(HttpStatus.BAD_REQUEST, "request body is missing or malformed");
    }

    /**
     * Money arithmetic is exact ({@code Math.addExact}/{@code Math.subtractExact}), so an amount
     * that would overflow a {@code BIGINT} balance ends up here as a 400 instead of wrapping
     * around into a wrong balance.
     */
    @ExceptionHandler(ArithmeticException.class)
    ResponseEntity<ErrorResponse> handleOverflow(ArithmeticException exception) {
        return respond(HttpStatus.BAD_REQUEST, "amount is out of the supported range");
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(status, message));
    }
}
