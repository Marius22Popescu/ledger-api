package com.ledger.account;

import com.ledger.api.ApiModels.OpenAccountRequest;
import com.ledger.api.Bodies;
import com.ledger.domain.Iso4217;
import com.ledger.domain.LedgerCurrency;
import com.ledger.domain.Money;
import com.ledger.error.ErrorCode;
import com.ledger.error.FieldViolation;
import com.ledger.error.Problems;
import com.ledger.idempotency.IdempotencyExecutor;
import com.ledger.idempotency.RequestHashes;
import com.ledger.idempotency.StoredResponse;
import com.ledger.json.LedgerJson;
import com.ledger.ledger.Outcome;
import com.ledger.store.LedgerStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class AccountService {

    private static final Pattern ACCOUNT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private final LedgerStore store;
    private final IdempotencyExecutor idempotency;
    private final LedgerJson json;
    private final Clock clock;

    public AccountService(LedgerStore store, IdempotencyExecutor idempotency, LedgerJson json, Clock clock) {
        this.store = store;
        this.idempotency = idempotency;
        this.json = json;
        this.clock = clock;
    }

    public StoredResponse open(String idempotencyKey, OpenAccountRequest request) {
        String hash = RequestHashes.sha256("POST /v1/accounts\n"
                + trim(request == null ? null : request.id()) + "\n"
                + trim(request == null ? null : request.currency()).toUpperCase(Locale.ROOT) + "\n"
                + (request == null || request.overdraftLimit() == null ? "" : trim(request.overdraftLimit())));
        return idempotency.execute(idempotencyKey, hash, () -> openInside(request));
    }

    private Outcome openInside(OpenAccountRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        String id = trim(request == null ? null : request.id());
        if (!ACCOUNT_ID.matcher(id).matches()) {
            violations.add(new FieldViolation("/id", ErrorCode.INVALID_REQUEST,
                    "Account id is required and may contain letters, digits, '.', '_' and '-'."));
        }

        String rawCurrency = trim(request == null ? null : request.currency());
        Optional<LedgerCurrency> currency = Optional.empty();
        if (!rawCurrency.matches("[A-Za-z]{3}")) {
            violations.add(new FieldViolation("/currency", ErrorCode.INVALID_CURRENCY,
                    "\"" + rawCurrency + "\" is not an ISO 4217 currency code."));
        } else {
            String code = rawCurrency.toUpperCase(Locale.ROOT);
            if (!Iso4217.isCurrencyCode(code)) {
                violations.add(new FieldViolation("/currency", ErrorCode.INVALID_CURRENCY,
                        code + " is not an ISO 4217 currency code."));
            } else {
                currency = LedgerCurrency.fromCode(code);
                if (currency.isEmpty()) {
                    violations.add(new FieldViolation("/currency", ErrorCode.UNSUPPORTED_CURRENCY,
                            code + " is a valid ISO 4217 currency code, but this ledger supports only EUR, USD and JPY."));
                }
            }
        }

        long overdraft = 0;
        if (currency.isPresent() && request != null && request.overdraftLimit() != null) {
            try {
                overdraft = Money.toNonNegativeMinorUnits(request.overdraftLimit(), currency.get().decimals());
            } catch (Money.InvalidAmountException ex) {
                String detail = ex.problem() == Money.Problem.SCALE
                        ? "Overdraft limit has more decimal places than " + currency.get().code() + " allows."
                        : "Overdraft limit must be zero or a positive decimal.";
                violations.add(new FieldViolation("/overdraftLimit", ErrorCode.INVALID_AMOUNT, detail));
            }
        } else if (request != null && request.overdraftLimit() != null && currency.isEmpty()
                && !trim(request.overdraftLimit()).matches("[0-9]+(\\.[0-9]+)?")) {
            violations.add(new FieldViolation("/overdraftLimit", ErrorCode.INVALID_AMOUNT,
                    "Overdraft limit must be zero or a positive decimal."));
        }

        if (!violations.isEmpty()) {
            return new Outcome.Failure(Problems.from(violations));
        }

        LedgerCurrency supported = currency.orElseThrow();
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean inserted = store.insertAccount(id, supported, overdraft, createdAt);
        if (!inserted) {
            return new Outcome.Failure(Problems.of(ErrorCode.ACCOUNT_EXISTS, "/id",
                    "Account " + id + " already exists."));
        }
        var account = store.findAccount(id).orElseThrow();
        String body = json.write(Bodies.account(account));
        return new Outcome.Success(201, body, "/v1/accounts/" + id);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
