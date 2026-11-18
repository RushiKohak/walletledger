package com.rushi.walletledger.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Translates exceptions into HTTP responses so controllers and services stay free of status codes.
 *
 * <p>Every failure leaves through here and every one of them uses the same JSON shape
 * ({@code status, error, message, timestamp}): a client parses errors the same way whether the
 * request was rejected by routing, by JSON binding, by validation or by a bug.
 *
 * <p>The last handler is the catch-all, and it is deliberately the only one that hides
 * information: an unexpected exception is a bug, so the real exception is logged where operators
 * can see it and the client receives a generic message. Stack traces and internal messages are
 * never part of a response body.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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

    /**
     * The key already means a different operation. 422 rather than 409: the request contradicts
     * the key it names, so retrying it unchanged can never succeed.
     */
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ResponseEntity<ErrorResponse> handleIdempotencyKeyConflict(IdempotencyKeyConflictException exception) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
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
     * A path variable or query parameter that is not a value of the type the endpoint takes, such
     * as {@code /accounts/abc} or {@code ?page=abc}. Answered as a 400 with our own body instead of
     * the container's conversion message, which would otherwise be a different shape.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return respond(HttpStatus.BAD_REQUEST,
                exception.getName() + " has an invalid value: " + exception.getValue());
    }

    /** A body sent as something other than JSON: the endpoint has nothing that can read it. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "content type is not supported; send application/json");
    }

    /** A method the matched route does not implement, for example DELETE on a collection. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED,
                "method " + exception.getMethod() + " is not supported for this path");
    }

    /**
     * A path that no controller or static resource matches. Without this, Spring answers it with a
     * 500 or an HTML error page, which would make a typo in a URL look like a server fault.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ErrorResponse> handleUnknownRoute(Exception exception) {
        return respond(HttpStatus.NOT_FOUND, "no endpoint matches this path");
    }

    /**
     * The bug path. The exception is logged in full, server-side, and the client is told only that
     * something failed: an internal message or a stack trace in a response would leak table names,
     * package structure and the shape of the code to anyone who can send a request.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        log.error("unhandled exception while serving a request", exception);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "an unexpected error occurred");
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
