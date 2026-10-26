package com.ledger.ledger;

import com.ledger.api.ApiModels.EntryRequest;
import com.ledger.api.ApiModels.TransactionRequest;
import com.ledger.api.Bodies;
import com.ledger.domain.Account;
import com.ledger.domain.Direction;
import com.ledger.domain.LedgerCurrency;
import com.ledger.domain.Money;
import com.ledger.error.ErrorCode;
import com.ledger.error.FieldViolation;
import com.ledger.error.Problems;
import com.ledger.idempotency.IdempotencyExecutor;
import com.ledger.idempotency.RequestHashes;
import com.ledger.idempotency.StoredResponse;
import com.ledger.json.LedgerJson;
import com.ledger.store.LedgerStore;
import com.ledger.store.StoredEntry;
import com.ledger.store.StoredTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PostingService {

    private static final Logger log = LoggerFactory.getLogger(PostingService.class);
    private static final int MAX_ENTRIES = 50;
    private static final Pattern ACCOUNT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private final LedgerStore store;
    private final IdempotencyExecutor idempotency;
    private final LedgerJson json;
    private final Clock clock;

    public PostingService(LedgerStore store, IdempotencyExecutor idempotency, LedgerJson json, Clock clock) {
        this.store = store;
        this.idempotency = idempotency;
        this.json = json;
        this.clock = clock;
    }

    public StoredResponse record(String idempotencyKey, String requestId, TransactionRequest request) {
        return idempotency.execute(idempotencyKey, transactionHash(request), () -> recordInside(requestId, request));
    }

    public StoredResponse reverse(String idempotencyKey, String requestId, String transactionId, String description) {
        String hash = RequestHashes.sha256("POST /v1/transactions/" + transactionId + "/reversals\n" + trim(description));
        return idempotency.execute(idempotencyKey, hash, () -> reverseInside(requestId, transactionId, description));
    }

    public Outcome post(PostCommand command) {
        List<FieldViolation> violations = new ArrayList<>();
        String description = trim(command.description());
        if (description.isEmpty()) {
            violations.add(new FieldViolation("/description", ErrorCode.INVALID_REQUEST, "A description is required."));
        } else if (description.length() > 500) {
            violations.add(new FieldViolation("/description", ErrorCode.INVALID_REQUEST, "Description must be at most 500 characters."));
        }

        List<EntryDraft> drafts = command.entries() == null ? List.of() : command.entries();
        if (drafts.size() < 2) {
            violations.add(new FieldViolation("/entries", ErrorCode.TOO_FEW_ENTRIES, "A transaction needs at least two entries."));
        } else if (drafts.size() > MAX_ENTRIES) {
            violations.add(new FieldViolation("/entries", ErrorCode.TOO_MANY_ENTRIES, "A transaction can have at most 50 entries."));
        }

        List<CheckedEntry> checked = new ArrayList<>();
        Set<String> accountIds = new LinkedHashSet<>();
        for (int i = 0; i < drafts.size(); i++) {
            EntryDraft draft = drafts.get(i);
            String pointer = draft.pointer() == null ? "/entries/" + i : draft.pointer();
            String accountId = trim(draft.accountId());
            if (!ACCOUNT_ID.matcher(accountId).matches()) {
                violations.add(new FieldViolation(pointer + "/accountId", ErrorCode.UNKNOWN_ACCOUNT,
                        accountId.isEmpty() ? "Account id is required." : "Account " + accountId + " does not exist."));
                continue;
            }
            Optional<Direction> direction = Direction.parse(draft.direction());
            if (direction.isEmpty()) {
                violations.add(new FieldViolation(pointer + "/direction", ErrorCode.INVALID_REQUEST,
                        "Direction must be DEBIT or CREDIT."));
                continue;
            }
            accountIds.add(accountId);
            checked.add(new CheckedEntry(accountId, direction.get(), trim(draft.amount()), pointer));
        }

        Map<String, Account> accounts = new HashMap<>();
        for (String accountId : accountIds) {
            store.findAccount(accountId).ifPresentOrElse(
                    account -> accounts.put(accountId, account),
                    () -> violations.add(unknown(accountId, pointerFor(checked, accountId)))
            );
        }

        Set<LedgerCurrency> currencies = new LinkedHashSet<>();
        accounts.values().forEach(account -> currencies.add(account.currency()));
        if (currencies.size() > 1) {
            violations.add(new FieldViolation("/entries", ErrorCode.CURRENCY_MISMATCH,
                    "Accounts must share one currency, found " + currencies + "."));
        }

        List<ParsedEntry> parsed = new ArrayList<>();
        boolean amountsOk = true;
        for (CheckedEntry entry : checked) {
            Account account = accounts.get(entry.accountId());
            if (account == null) {
                amountsOk = false;
                continue;
            }
            try {
                long minor = Money.toMinorUnits(entry.amount(), account.currency().decimals());
                parsed.add(new ParsedEntry(entry.accountId(), entry.direction(), minor, entry.pointer()));
            } catch (Money.InvalidAmountException ex) {
                amountsOk = false;
                violations.add(amountViolation(entry, account.currency(), ex));
            }
        }

        if (amountsOk && currencies.size() == 1 && checked.size() == parsed.size() && parsed.size() >= 2) {
            long debits = 0;
            long credits = 0;
            for (ParsedEntry entry : parsed) {
                if (entry.direction() == Direction.DEBIT) {
                    debits = Math.addExact(debits, entry.amountMinor());
                } else {
                    credits = Math.addExact(credits, entry.amountMinor());
                }
            }
            if (debits != credits) {
                LedgerCurrency currency = currencies.iterator().next();
                violations.add(new FieldViolation("/entries", ErrorCode.UNBALANCED,
                        "Debits total " + Money.format(debits, currency.decimals()) + " " + currency.code()
                                + " and credits total " + Money.format(credits, currency.decimals()) + " " + currency.code() + "."));
            }
        }

        if (!violations.isEmpty()) {
            return new Outcome.Failure(Problems.from(violations));
        }

        LedgerCurrency currency = currencies.iterator().next();
        List<Account> locked = store.lockAccounts(accountIds);
        Map<String, Account> lockedById = new HashMap<>();
        locked.forEach(account -> lockedById.put(account.id(), account));
        if (lockedById.size() != accountIds.size()) {
            List<FieldViolation> missing = new ArrayList<>();
            for (String accountId : accountIds) {
                if (!lockedById.containsKey(accountId)) {
                    missing.add(unknown(accountId, pointerFor(checked, accountId)));
                }
            }
            return new Outcome.Failure(Problems.from(missing));
        }

        if (command.reversesTransactionId() != null) {
            Outcome reversalProblem = reversalGuard(command.reversesTransactionId());
            if (reversalProblem != null) {
                return reversalProblem;
            }
        }

        Map<String, Long> delta = new HashMap<>();
        for (ParsedEntry entry : parsed) {
            long signed = entry.direction() == Direction.CREDIT ? entry.amountMinor() : Math.negateExact(entry.amountMinor());
            delta.merge(entry.accountId(), signed, Math::addExact);
        }
        List<FieldViolation> overdrafts = new ArrayList<>();
        for (Account account : locked) {
            long next = Math.addExact(account.balanceMinor(), delta.getOrDefault(account.id(), 0L));
            Long limit = account.overdraftLimitMinor();
            if (limit != null && next < Math.negateExact(limit)) {
                overdrafts.add(new FieldViolation(pointerFor(checked, account.id()), ErrorCode.OVERDRAFT_LIMIT,
                        "Account " + account.id() + " would go to " + Money.format(next, account.currency().decimals())
                                + " " + account.currency().code() + ", past its overdraft limit of "
                                + Money.format(limit, account.currency().decimals()) + " " + account.currency().code() + "."));
            }
        }
        if (!overdrafts.isEmpty()) {
            return new Outcome.Failure(Problems.from(overdrafts));
        }

        Map<String, Long> running = new HashMap<>();
        locked.forEach(account -> running.put(account.id(), account.balanceMinor()));
        List<AppliedEntry> applied = new ArrayList<>();
        int ordinal = 0;
        for (ParsedEntry entry : parsed) {
            long current = running.get(entry.accountId());
            long next = entry.direction() == Direction.CREDIT
                    ? Math.addExact(current, entry.amountMinor())
                    : Math.subtractExact(current, entry.amountMinor());
            running.put(entry.accountId(), next);
            applied.add(new AppliedEntry(ordinal++, entry, next));
        }

        String transactionId = UUID.randomUUID().toString();
        Instant recordedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean inserted = store.insertTransaction(
                transactionId,
                description,
                currency,
                recordedAt,
                command.reversesTransactionId(),
                command.paymentId(),
                requestId(command.requestId())
        );
        if (!inserted) {
            return duplicate(command);
        }
        for (AppliedEntry entry : applied) {
            store.insertEntry(
                    transactionId,
                    entry.ordinal(),
                    entry.parsed().accountId(),
                    entry.parsed().direction(),
                    entry.parsed().amountMinor(),
                    entry.balanceAfter(),
                    recordedAt
            );
        }
        for (Account account : locked) {
            store.updateBalance(account.id(), running.get(account.id()));
        }

        StoredTransaction stored = store.findTransaction(transactionId).orElseThrow(
                () -> new IllegalStateException("recorded transaction not readable: " + transactionId)
        );
        String body = json.write(Bodies.transaction(stored));
        store.insertOutbox(transactionId, body, recordedAt);
        log.info("transaction.recorded id={} sequence={} requestId={} paymentId={} accounts={} result=recorded",
                stored.id(), stored.sequence(), stored.requestId(), stored.paymentId(),
                stored.entries().stream().map(entry -> entry.accountId()).distinct().toList());
        return new Outcome.Success(201, body, "/v1/transactions/" + transactionId);
    }

    private Outcome recordInside(String requestId, TransactionRequest request) {
        List<EntryDraft> drafts = new ArrayList<>();
        if (request != null && request.entries() != null) {
            for (int i = 0; i < request.entries().size(); i++) {
                EntryRequest entry = request.entries().get(i);
                if (entry == null) {
                    drafts.add(new EntryDraft(null, null, null, "/entries/" + i));
                } else {
                    drafts.add(new EntryDraft(entry.accountId(), entry.direction(), entry.amount(), "/entries/" + i));
                }
            }
        }
        String description = request == null ? null : request.description();
        return post(new PostCommand(description, drafts, null, null, requestId));
    }

    private Outcome reverseInside(String requestId, String transactionId, String description) {
        StoredTransaction original = store.findTransaction(transactionId).orElse(null);
        if (original == null) {
            return new Outcome.Failure(Problems.of(ErrorCode.NOT_FOUND, "/transactionId",
                    "Transaction " + transactionId + " does not exist."));
        }
        if (original.reversesTransactionId() != null) {
            return new Outcome.Failure(Problems.of(ErrorCode.REVERSAL_NOT_REVERSIBLE, "/transactionId",
                    "Transaction " + transactionId + " is itself a reversal and cannot be reversed."));
        }
        if (original.reversedByTransactionId() != null) {
            return new Outcome.Failure(Problems.of(ErrorCode.ALREADY_REVERSED, "/transactionId",
                    "Transaction " + transactionId + " has already been reversed."));
        }
        String reversalDescription = trim(description);
        if (reversalDescription.isEmpty()) {
            reversalDescription = "Reversal of " + transactionId;
        }
        List<EntryDraft> flipped = new ArrayList<>();
        for (StoredEntry entry : original.entries()) {
            flipped.add(new EntryDraft(
                    entry.accountId(),
                    entry.direction().flipped().name(),
                    Money.format(entry.amountMinor(), original.currency().decimals()),
                    "/entries/" + entry.ordinal()
            ));
        }
        return post(new PostCommand(reversalDescription, flipped, transactionId, null, requestId));
    }

    private Outcome reversalGuard(String transactionId) {
        store.lockTransaction(transactionId);
        StoredTransaction original = store.findTransaction(transactionId).orElse(null);
        if (original == null) {
            return new Outcome.Failure(Problems.of(ErrorCode.NOT_FOUND, "/transactionId",
                    "Transaction " + transactionId + " does not exist."));
        }
        if (original.reversesTransactionId() != null) {
            return new Outcome.Failure(Problems.of(ErrorCode.REVERSAL_NOT_REVERSIBLE, "/transactionId",
                    "Transaction " + transactionId + " is itself a reversal and cannot be reversed."));
        }
        if (store.lockReversalOf(transactionId).isPresent()) {
            return new Outcome.Failure(Problems.of(ErrorCode.ALREADY_REVERSED, "/transactionId",
                    "Transaction " + transactionId + " has already been reversed."));
        }
        return null;
    }

    private Outcome duplicate(PostCommand command) {
        if (command.paymentId() != null) {
            StoredTransaction existing = store.findByPaymentId(command.paymentId()).orElse(null);
            if (existing != null && samePayment(command, existing)) {
                return new Outcome.AlreadyApplied();
            }
            return new Outcome.Failure(Problems.of(ErrorCode.PAYMENT_CONFLICT, "/paymentId",
                    "Payment " + command.paymentId() + " was already recorded with different details."));
        }
        if (command.reversesTransactionId() != null) {
            return new Outcome.Failure(Problems.of(ErrorCode.ALREADY_REVERSED, "/transactionId",
                    "Transaction " + command.reversesTransactionId() + " has already been reversed."));
        }
        throw new IllegalStateException("unexpected duplicate transaction insert");
    }

    private boolean samePayment(PostCommand command, StoredTransaction existing) {
        String merchant = null;
        String amount = null;
        for (EntryDraft entry : command.entries()) {
            if (Direction.parse(entry.direction()).orElse(null) == Direction.CREDIT) {
                merchant = trim(entry.accountId());
                amount = trim(entry.amount());
            }
        }
        StoredEntry credit = existing.entries().stream()
                .filter(entry -> entry.direction() == Direction.CREDIT)
                .findFirst()
                .orElse(null);
        if (merchant == null || amount == null || credit == null || !merchant.equals(credit.accountId())) {
            return false;
        }
        try {
            long minor = Money.toMinorUnits(amount, existing.currency().decimals());
            return minor == credit.amountMinor();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String transactionHash(TransactionRequest request) {
        StringBuilder canonical = new StringBuilder("POST /v1/transactions\n");
        canonical.append(request == null ? "" : trim(request.description())).append('\n');
        if (request != null && request.entries() != null) {
            for (EntryRequest entry : request.entries()) {
                if (entry == null) {
                    canonical.append("||\n");
                    continue;
                }
                canonical.append(trim(entry.accountId())).append('|')
                        .append(trim(entry.direction()).toUpperCase(Locale.ROOT)).append('|')
                        .append(trim(entry.amount())).append('\n');
            }
        }
        return RequestHashes.sha256(canonical.toString());
    }

    private static FieldViolation amountViolation(CheckedEntry entry, LedgerCurrency currency, Money.InvalidAmountException ex) {
        String pointer = entry.pointer() + "/amount";
        return switch (ex.problem()) {
            case SCALE -> new FieldViolation(pointer, ErrorCode.INVALID_AMOUNT_SCALE,
                    "Amount " + entry.amount() + " has more decimal places than " + currency.code() + " allows (" + currency.decimals() + ").");
            case NOT_POSITIVE -> new FieldViolation(pointer, ErrorCode.INVALID_AMOUNT, "Amount must be positive.");
            case FORMAT -> new FieldViolation(pointer, ErrorCode.INVALID_AMOUNT,
                    "Amount must be a positive decimal such as 10.00 or 15000.");
            case OVERFLOW -> new FieldViolation(pointer, ErrorCode.INVALID_AMOUNT, "Amount is too large.");
        };
    }

    private static FieldViolation unknown(String accountId, String pointer) {
        return new FieldViolation(pointer, ErrorCode.UNKNOWN_ACCOUNT, "Account " + accountId + " does not exist.");
    }

    private static String pointerFor(List<CheckedEntry> entries, String accountId) {
        for (CheckedEntry entry : entries) {
            if (accountId.equals(entry.accountId())) {
                return entry.pointer() + "/accountId";
            }
        }
        return "/entries";
    }

    private static String requestId(String value) {
        String trimmed = trim(value);
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= 128 ? trimmed : trimmed.substring(0, 128);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record CheckedEntry(String accountId, Direction direction, String amount, String pointer) {
    }

    private record ParsedEntry(String accountId, Direction direction, long amountMinor, String pointer) {
    }

    private record AppliedEntry(int ordinal, ParsedEntry parsed, long balanceAfter) {
    }
}
