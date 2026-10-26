package com.ledger.api;

import com.ledger.error.ErrorCode;
import com.ledger.error.LedgerExceptions.IdempotencyConflictException;
import com.ledger.error.LedgerExceptions.NotFoundException;
import com.ledger.error.LedgerExceptions.RequestException;
import com.ledger.error.ProblemBody;
import com.ledger.error.Problems;
import com.ledger.json.LedgerJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class LedgerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LedgerExceptionHandler.class);

    private final LedgerJson json;

    public LedgerExceptionHandler(LedgerJson json) {
        this.json = json;
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<String> notFound(NotFoundException ex) {
        return problem(ex.problem());
    }

    @ExceptionHandler(RequestException.class)
    public ResponseEntity<String> request(RequestException ex) {
        return problem(ex.problem());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<String> idempotency(IdempotencyConflictException ex) {
        return problem(ex.problem());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<String> missingHeader(MissingRequestHeaderException ex) {
        if ("Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())) {
            return problem(Problems.of(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "/Idempotency-Key",
                    "Send an Idempotency-Key header. A retry with the same key and the same request is applied once."));
        }
        return problem(Problems.of(ErrorCode.INVALID_REQUEST, "/" + ex.getHeaderName(), "Required header is missing."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> unreadable(HttpMessageNotReadableException ex) {
        return problem(Problems.of(ErrorCode.INVALID_REQUEST, "/", "Request body is not valid JSON."));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<String> dataAccess(DataAccessException ex) {
        log.error("data access failure", ex);
        return problem(Problems.of(ErrorCode.TEMPORARILY_UNAVAILABLE, "/",
                "The ledger could not finish the request. Retry the same request."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> fallback(Exception ex) {
        log.error("unhandled failure", ex);
        return problem(Problems.of(ErrorCode.INTERNAL, "/", "The ledger failed to process the request."));
    }

    private ResponseEntity<String> problem(ProblemBody body) {
        return ResponseEntity.status(body.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(json.write(body));
    }
}
