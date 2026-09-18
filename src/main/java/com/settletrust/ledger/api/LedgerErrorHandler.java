package com.settletrust.ledger.api;

import com.settletrust.ledger.TransferRejected;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

import static com.settletrust.ledger.api.LedgerDtos.ErrorView;

/**
 * Turns a refusal into a status code.
 *
 * <p>The distinction that matters: a 4xx here means the request was understood and the
 * ledger declined it, and the client should not retry unchanged. Nothing in this class
 * decides whether a transfer is legal; it only decides how to say no.
 */
@RestControllerAdvice
class LedgerErrorHandler {

    @ExceptionHandler(TransferRejected.class)
    ResponseEntity<ErrorView> rejected(TransferRejected rejection) {
        return ResponseEntity
                .status(statusFor(rejection.reason()))
                .body(new ErrorView(rejection.reason().name(), rejection.getMessage()));
    }

    private static HttpStatus statusFor(TransferRejected.Reason reason) {
        return switch (reason) {
            // The account named does not exist, so the resource is not there to act on.
            case UNKNOWN_ACCOUNT -> HttpStatus.NOT_FOUND;
            // The request is malformed in the plain sense: nobody transfers zero, and
            // nobody transfers to themselves on purpose.
            case AMOUNT_NOT_POSITIVE, SAME_ACCOUNT -> HttpStatus.BAD_REQUEST;
            // Well formed, understood, and refused by the ledger's own rules. Retrying it
            // unchanged will fail identically, which is what 422 tells a client.
            case CURRENCY_MISMATCH, INSUFFICIENT_FUNDS -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    /** An account id that is already taken. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorView> conflict(IllegalStateException failure) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorView("CONFLICT", failure.getMessage()));
    }

    /** A blank idempotency key, a malformed account id, an unusable currency code. */
    @ExceptionHandler(MalformedRequest.class)
    ResponseEntity<ErrorView> malformed(MalformedRequest failure) {
        return ResponseEntity
                .badRequest()
                .body(new ErrorView("MALFORMED_REQUEST", failure.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorView> invalidBody(MethodArgumentNotValidException failure) {
        String detail = failure.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity
                .badRequest()
                .body(new ErrorView("INVALID_REQUEST", detail));
    }
}
