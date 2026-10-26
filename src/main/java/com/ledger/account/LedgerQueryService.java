package com.ledger.account;

import com.ledger.api.ApiModels.HistoricalBalance;
import com.ledger.api.ApiModels.StatementPage;
import com.ledger.api.ApiModels.TransactionFeed;
import com.ledger.api.Bodies;
import com.ledger.domain.Account;
import com.ledger.domain.Money;
import com.ledger.error.ErrorCode;
import com.ledger.error.LedgerExceptions.NotFoundException;
import com.ledger.error.LedgerExceptions.RequestException;
import com.ledger.error.Problems;
import com.ledger.store.LedgerStore;
import com.ledger.store.StatementLine;
import com.ledger.store.StoredTransaction;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

@Service
public class LedgerQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final LedgerStore store;

    public LedgerQueryService(LedgerStore store) {
        this.store = store;
    }

    public Account account(String id) {
        return store.findAccount(id).orElseThrow(() -> new NotFoundException("/accountId", "Account " + id + " does not exist."));
    }

    public StoredTransaction transaction(String id) {
        return store.findTransaction(id).orElseThrow(() -> new NotFoundException("/transactionId", "Transaction " + id + " does not exist."));
    }

    public StatementPage statement(String accountId, String cursor, Integer limit) {
        Account account = account(accountId);
        int pageSize = pageSize(limit);
        Cursor position = decode(cursor);
        List<StatementLine> lines = position == null
                ? store.statement(account.id(), null, null, pageSize + 1)
                : store.statement(account.id(), position.recordedAt(), position.entryId(), pageSize + 1);
        boolean more = lines.size() > pageSize;
        if (more) {
            lines = lines.subList(0, pageSize);
        }
        String next = null;
        if (more) {
            StatementLine last = lines.get(lines.size() - 1);
            next = encode(last.recordedAt(), last.entryId());
        }
        return new StatementPage(account.id(), lines.stream().map(Bodies::statementItem).toList(), next);
    }

    public HistoricalBalance balanceAt(String accountId, String at) {
        Account account = account(accountId);
        Instant instant = parseInstant(at);
        long minor = store.balanceAt(account.id(), instant).orElse(0L);
        return new HistoricalBalance(
                account.id(),
                account.currency().code(),
                Money.format(minor, account.currency().decimals()),
                Bodies.time(instant)
        );
    }

    public TransactionFeed feed(Long afterSequence, Integer limit) {
        long after = afterSequence == null ? 0L : afterSequence;
        if (after < 0) {
            throw new RequestException(Problems.of(ErrorCode.INVALID_REQUEST, "/afterSequence", "afterSequence must be zero or greater."));
        }
        int pageSize = pageSize(limit);
        List<StoredTransaction> rows = store.feed(after, pageSize + 1);
        boolean more = rows.size() > pageSize;
        if (more) {
            rows = rows.subList(0, pageSize);
        }
        Long next = more ? rows.get(rows.size() - 1).sequence() : null;
        return new TransactionFeed(rows.stream().map(Bodies::transaction).toList(), next);
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new RequestException(Problems.of(ErrorCode.INVALID_REQUEST, "/limit", "limit must be between 1 and 100."));
        }
        return limit;
    }

    private static Instant parseInstant(String at) {
        if (at == null || at.isBlank()) {
            throw new RequestException(Problems.of(ErrorCode.INVALID_REQUEST, "/at", "Query parameter at is required, as an ISO-8601 instant."));
        }
        try {
            return Instant.parse(at.trim());
        } catch (DateTimeParseException ex) {
            throw new RequestException(Problems.of(ErrorCode.INVALID_REQUEST, "/at", "at must be an ISO-8601 instant, such as 2026-10-26T12:00:00Z."));
        }
    }

    private static String encode(Instant recordedAt, long entryId) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, recordedAt);
        String raw = micros + ":" + entryId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("shape");
            }
            long micros = Long.parseLong(parts[0]);
            long entryId = Long.parseLong(parts[1]);
            return new Cursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), entryId);
        } catch (RuntimeException ex) {
            throw new RequestException(Problems.of(ErrorCode.INVALID_REQUEST, "/cursor", "cursor is not a cursor returned by this ledger."));
        }
    }

    private record Cursor(Instant recordedAt, long entryId) {
    }
}
