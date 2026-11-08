package dev.seatline.api.web;

import dev.seatline.ledger.client.LedgerClient;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/** Every error comes back as {"error": "some_code"} and never includes internals. */
@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.code()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> badInput(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_request"));
    }

    /** The ledger didn't answer in time (no leader, or too few nodes up). Safe for the client to retry. */
    @ExceptionHandler(LedgerClient.UnavailableException.class)
    ResponseEntity<Map<String, String>> ledgerDown(LedgerClient.UnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "try_again"));
    }

    /**
     * Every database connection is busy (a rush bigger than the pool). Said straight away as "try
     * again" instead of a 500; checkout is idempotent, so a retry can't charge twice.
     */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class,
            TransientDataAccessException.class, org.hibernate.exception.JDBCConnectionException.class})
    ResponseEntity<Map<String, String>> databaseBusy(Exception e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "try_again"));
    }
}
