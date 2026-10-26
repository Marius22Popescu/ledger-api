package com.ledger.idempotency;

import com.ledger.error.ErrorCode;
import com.ledger.error.LedgerExceptions.IdempotencyConflictException;
import com.ledger.error.LedgerExceptions.RequestException;
import com.ledger.error.Problems;
import com.ledger.json.LedgerJson;
import com.ledger.ledger.Outcome;
import com.ledger.store.IdempotencyRow;
import com.ledger.store.LedgerStore;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Component
public class IdempotencyExecutor {

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_.:-]{1,200}$");

    private final TransactionTemplate transactions;
    private final LedgerStore store;
    private final LedgerJson json;
    private final Clock clock;

    public IdempotencyExecutor(PlatformTransactionManager manager, LedgerStore store, LedgerJson json, Clock clock) {
        this.transactions = new TransactionTemplate(manager);
        this.store = store;
        this.json = json;
        this.clock = clock;
    }

    public StoredResponse execute(String key, String requestHash, Supplier<Outcome> work) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new RequestException(Problems.of(
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "/Idempotency-Key",
                    "Send an Idempotency-Key of 1 to 200 characters using letters, digits, and . _ : -."
            ));
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> run(key, requestHash, work));
            } catch (TransientDataAccessException ex) {
                if (attempt >= 5) {
                    throw ex;
                }
            }
        }
    }

    private StoredResponse run(String key, String requestHash, Supplier<Outcome> work) {
        boolean claimed = store.claimIdempotency(key, requestHash, clock.instant());
        if (!claimed) {
            IdempotencyRow existing = store.findIdempotency(key).orElseThrow(
                    () -> new IllegalStateException("idempotency key disappeared: " + key)
            );
            if (!existing.requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException();
            }
            if (!existing.completed()) {
                throw new RequestException(Problems.of(
                        ErrorCode.TEMPORARILY_UNAVAILABLE,
                        "/Idempotency-Key",
                        "This request is still in progress. Retry it with the same Idempotency-Key."
                ));
            }
            return new StoredResponse(existing.httpStatus(), existing.responseBody(), existing.location(), true);
        }
        Outcome outcome = work.get();
        if (outcome instanceof Outcome.Success success) {
            store.completeIdempotency(key, success.status(), success.body(), success.location());
            return new StoredResponse(success.status(), success.body(), success.location(), false);
        }
        if (outcome instanceof Outcome.Failure failure) {
            String body = json.write(failure.problem());
            store.completeIdempotency(key, failure.problem().status(), body, null);
            return new StoredResponse(failure.problem().status(), body, null, false);
        }
        throw new IllegalStateException("payment deduplication is not an HTTP idempotency outcome");
    }
}
