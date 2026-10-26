package com.ledger.store;

import com.ledger.domain.Account;
import com.ledger.domain.Direction;
import com.ledger.domain.LedgerCurrency;
import org.springframework.dao.UncategorizedDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class LedgerStore {

    private static final RowMapper<Account> ACCOUNT = (rs, row) -> new Account(
            rs.getString("id"),
            LedgerCurrency.valueOf(rs.getString("currency")),
            rs.getObject("overdraft_limit_minor") == null ? null : rs.getLong("overdraft_limit_minor"),
            rs.getLong("balance_minor"),
            SqlTimes.read(rs, "created_at")
    );

    private final JdbcTemplate jdbc;

    public LedgerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findAccount(String id) {
        List<Account> rows = jdbc.query(
                "SELECT id, currency, overdraft_limit_minor, balance_minor, created_at FROM accounts WHERE id = ?",
                ACCOUNT,
                id
        );
        return rows.stream().findFirst();
    }

    /**
     * Locks one row at a time in account-id order so A→B and B→A cannot deadlock.
     */
    public List<Account> lockAccounts(Collection<String> ids) {
        List<String> ordered = ids.stream().distinct().sorted().toList();
        List<Account> locked = new ArrayList<>(ordered.size());
        for (String id : ordered) {
            locked.addAll(jdbc.query(
                    "SELECT id, currency, overdraft_limit_minor, balance_minor, created_at FROM accounts WHERE id = ? FOR UPDATE",
                    ACCOUNT,
                    id
            ));
        }
        return locked;
    }

    public boolean insertAccount(String id, LedgerCurrency currency, long overdraftLimitMinor, Instant createdAt) {
        return insert(
                "INSERT INTO accounts (id, currency, overdraft_limit_minor, balance_minor, created_at) VALUES (?, ?, ?, 0, ?)",
                ps -> {
                    ps.setString(1, id);
                    ps.setString(2, currency.code());
                    ps.setLong(3, overdraftLimitMinor);
                    SqlTimes.write(ps, 4, createdAt);
                }
        );
    }

    public void updateBalance(String id, long balanceMinor) {
        jdbc.update("UPDATE accounts SET balance_minor = ? WHERE id = ?", balanceMinor, id);
    }

    public boolean insertTransaction(
            String id,
            String description,
            LedgerCurrency currency,
            Instant recordedAt,
            String reversesTransactionId,
            String paymentId,
            String requestId
    ) {
        return insert(
                """
                INSERT INTO transactions
                    (id, description, currency, recorded_at, reverses_transaction_id, payment_id, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                ps -> {
                    ps.setString(1, id);
                    ps.setString(2, description);
                    ps.setString(3, currency.code());
                    SqlTimes.write(ps, 4, recordedAt);
                    nullableString(ps, 5, reversesTransactionId);
                    nullableString(ps, 6, paymentId);
                    nullableString(ps, 7, requestId);
                }
        );
    }

    public long sequenceOf(String transactionId) {
        Long sequence = jdbc.queryForObject(
                "SELECT sequence_no FROM transactions WHERE id = ?",
                Long.class,
                transactionId
        );
        if (sequence == null) {
            throw new IllegalStateException("transaction sequence missing for " + transactionId);
        }
        return sequence;
    }

    public Optional<StoredTransaction> findTransaction(String id) {
        List<StoredTransaction> rows = jdbc.query(
                """
                SELECT t.id, t.sequence_no, t.description, t.currency, t.recorded_at,
                       t.reverses_transaction_id, rev.id AS reversed_by, t.payment_id, t.request_id
                FROM transactions t
                LEFT JOIN transactions rev ON rev.reverses_transaction_id = t.id
                WHERE t.id = ?
                """,
                transactionMapper(false),
                id
        );
        return rows.stream().findFirst().map(this::withEntries);
    }

    public Optional<StoredTransaction> findByPaymentId(String paymentId) {
        List<StoredTransaction> rows = jdbc.query(
                """
                SELECT t.id, t.sequence_no, t.description, t.currency, t.recorded_at,
                       t.reverses_transaction_id, rev.id AS reversed_by, t.payment_id, t.request_id
                FROM transactions t
                LEFT JOIN transactions rev ON rev.reverses_transaction_id = t.id
                WHERE t.payment_id = ?
                """,
                transactionMapper(false),
                paymentId
        );
        return rows.stream().findFirst().map(this::withEntries);
    }

    public void lockTransaction(String id) {
        jdbc.query("SELECT id FROM transactions WHERE id = ? FOR UPDATE", (rs, row) -> rs.getString("id"), id);
    }

    public Optional<String> lockReversalOf(String transactionId) {
        List<String> rows = jdbc.query(
                "SELECT id FROM transactions WHERE reverses_transaction_id = ? FOR UPDATE",
                (rs, row) -> rs.getString("id"),
                transactionId
        );
        return rows.stream().findFirst();
    }

    public List<StoredTransaction> feed(long afterSequence, int limit) {
        List<StoredTransaction> headers = jdbc.query(
                """
                SELECT t.id, t.sequence_no, t.description, t.currency, t.recorded_at,
                       t.reverses_transaction_id, rev.id AS reversed_by, t.payment_id, t.request_id
                FROM transactions t
                LEFT JOIN transactions rev ON rev.reverses_transaction_id = t.id
                WHERE t.sequence_no > ?
                ORDER BY t.sequence_no
                LIMIT ?
                """,
                transactionMapper(false),
                afterSequence,
                limit
        );
        return headers.stream().map(this::withEntries).toList();
    }

    public void insertEntry(
            String transactionId,
            int ordinal,
            String accountId,
            Direction direction,
            long amountMinor,
            long balanceAfterMinor,
            Instant recordedAt
    ) {
        jdbc.update(
                """
                INSERT INTO entries
                    (transaction_id, ordinal_no, account_id, direction, amount_minor, balance_after_minor, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (PreparedStatement ps) -> {
                    ps.setString(1, transactionId);
                    ps.setInt(2, ordinal);
                    ps.setString(3, accountId);
                    ps.setString(4, direction.name());
                    ps.setLong(5, amountMinor);
                    ps.setLong(6, balanceAfterMinor);
                    SqlTimes.write(ps, 7, recordedAt);
                }
        );
    }

    public List<StatementLine> statement(String accountId, Instant recordedAt, Long entryId, int limit) {
        if (recordedAt == null) {
            return jdbc.query(
                    """
                    SELECT e.id, e.transaction_id, e.direction, e.amount_minor, e.balance_after_minor,
                           e.recorded_at, t.description, a.currency
                    FROM entries e
                    JOIN transactions t ON t.id = e.transaction_id
                    JOIN accounts a ON a.id = e.account_id
                    WHERE e.account_id = ?
                    ORDER BY e.recorded_at DESC, e.id DESC
                    LIMIT ?
                    """,
                    statementMapper(),
                    accountId,
                    limit
            );
        }
        return jdbc.query(
                """
                SELECT e.id, e.transaction_id, e.direction, e.amount_minor, e.balance_after_minor,
                       e.recorded_at, t.description, a.currency
                FROM entries e
                JOIN transactions t ON t.id = e.transaction_id
                JOIN accounts a ON a.id = e.account_id
                WHERE e.account_id = ?
                  AND (e.recorded_at < ? OR (e.recorded_at = ? AND e.id < ?))
                ORDER BY e.recorded_at DESC, e.id DESC
                LIMIT ?
                """,
                (PreparedStatement ps) -> {
                    ps.setString(1, accountId);
                    SqlTimes.write(ps, 2, recordedAt);
                    SqlTimes.write(ps, 3, recordedAt);
                    ps.setLong(4, entryId);
                    ps.setInt(5, limit);
                },
                statementMapper()
        );
    }

    public Optional<Long> balanceAt(String accountId, Instant at) {
        List<Long> rows = jdbc.query(
                """
                SELECT balance_after_minor
                FROM entries
                WHERE account_id = ? AND recorded_at <= ?
                ORDER BY recorded_at DESC, id DESC
                LIMIT 1
                """,
                (PreparedStatement ps) -> {
                    ps.setString(1, accountId);
                    SqlTimes.write(ps, 2, at);
                },
                (rs, row) -> rs.getLong("balance_after_minor")
        );
        return rows.stream().findFirst();
    }

    public boolean claimIdempotency(String key, String requestHash, Instant createdAt) {
        return insert(
                """
                INSERT INTO idempotency_records
                    (idempotency_key, request_hash, http_status, response_body, completed, created_at)
                VALUES (?, ?, 0, '', 0, ?)
                """,
                ps -> {
                    ps.setString(1, key);
                    ps.setString(2, requestHash);
                    SqlTimes.write(ps, 3, createdAt);
                }
        );
    }

    public Optional<IdempotencyRow> findIdempotency(String key) {
        List<IdempotencyRow> rows = jdbc.query(
                """
                SELECT idempotency_key, request_hash, http_status, response_body, location, completed
                FROM idempotency_records
                WHERE idempotency_key = ?
                """,
                (rs, row) -> new IdempotencyRow(
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        rs.getInt("http_status"),
                        rs.getString("response_body"),
                        rs.getString("location"),
                        rs.getInt("completed") == 1
                ),
                key
        );
        return rows.stream().findFirst();
    }

    public void completeIdempotency(String key, int httpStatus, String responseBody, String location) {
        jdbc.update(
                """
                UPDATE idempotency_records
                SET http_status = ?, response_body = ?, location = ?, completed = 1
                WHERE idempotency_key = ?
                """,
                (PreparedStatement ps) -> {
                    ps.setInt(1, httpStatus);
                    ps.setString(2, responseBody);
                    nullableString(ps, 3, location);
                    ps.setString(4, key);
                }
        );
    }

    public void insertOutbox(String transactionId, String payload, Instant createdAt) {
        jdbc.update(
                "INSERT INTO outbox_messages (transaction_id, payload, created_at) VALUES (?, ?, ?)",
                (PreparedStatement ps) -> {
                    ps.setString(1, transactionId);
                    ps.setString(2, payload);
                    SqlTimes.write(ps, 3, createdAt);
                }
        );
    }

    public List<OutboxRow> lockUnpublished(int limit) {
        return jdbc.query(
                """
                SELECT id, transaction_id, payload
                FROM outbox_messages
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, row) -> new OutboxRow(rs.getLong("id"), rs.getString("transaction_id"), rs.getString("payload")),
                limit
        );
    }

    public void markPublished(List<Long> ids, Instant publishedAt) {
        for (Long id : ids) {
            jdbc.update(
                    "UPDATE outbox_messages SET published_at = ? WHERE id = ?",
                    (PreparedStatement ps) -> {
                        SqlTimes.write(ps, 1, publishedAt);
                        ps.setLong(2, id);
                    }
            );
        }
    }

    public void insertFailure(String eventId, String paymentId, String reasonCode, String detail, String rawBody, Instant createdAt) {
        jdbc.update(
                """
                INSERT INTO payment_intake_failures
                    (event_id, payment_id, reason_code, detail, raw_body, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (PreparedStatement ps) -> {
                    nullableString(ps, 1, truncate(eventId, 128));
                    nullableString(ps, 2, truncate(paymentId, 128));
                    ps.setString(3, truncate(reasonCode, 64));
                    ps.setString(4, truncate(detail, 2000));
                    ps.setString(5, rawBody == null ? "" : rawBody);
                    SqlTimes.write(ps, 6, createdAt);
                }
        );
    }

    private StoredTransaction withEntries(StoredTransaction header) {
        List<StoredEntry> entries = jdbc.query(
                """
                SELECT id, account_id, direction, amount_minor, balance_after_minor, ordinal_no
                FROM entries
                WHERE transaction_id = ?
                ORDER BY ordinal_no
                """,
                (rs, row) -> new StoredEntry(
                        rs.getLong("id"),
                        rs.getString("account_id"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getLong("amount_minor"),
                        rs.getLong("balance_after_minor"),
                        rs.getInt("ordinal_no")
                ),
                header.id()
        );
        return new StoredTransaction(
                header.id(),
                header.sequence(),
                header.description(),
                header.currency(),
                header.recordedAt(),
                header.reversesTransactionId(),
                header.reversedByTransactionId(),
                header.paymentId(),
                header.requestId(),
                entries
        );
    }

    private static RowMapper<StoredTransaction> transactionMapper(boolean unused) {
        return (rs, row) -> new StoredTransaction(
                rs.getString("id"),
                rs.getLong("sequence_no"),
                rs.getString("description"),
                LedgerCurrency.valueOf(rs.getString("currency")),
                SqlTimes.read(rs, "recorded_at"),
                rs.getString("reverses_transaction_id"),
                rs.getString("reversed_by"),
                rs.getString("payment_id"),
                rs.getString("request_id"),
                List.of()
        );
    }

    private static RowMapper<StatementLine> statementMapper() {
        return (rs, row) -> new StatementLine(
                rs.getLong("id"),
                rs.getString("transaction_id"),
                Direction.valueOf(rs.getString("direction")),
                rs.getLong("amount_minor"),
                rs.getLong("balance_after_minor"),
                SqlTimes.read(rs, "recorded_at"),
                rs.getString("description"),
                LedgerCurrency.valueOf(rs.getString("currency"))
        );
    }

    private boolean insert(String sql, PreparedStatementSetter setter) {
        Boolean inserted = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            Savepoint savepoint = connection.setSavepoint("ledger_insert");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                setter.setValues(statement);
                statement.executeUpdate();
                return true;
            } catch (SQLException ex) {
                connection.rollback(savepoint);
                if (isDuplicate(ex)) {
                    return false;
                }
                throw new UncategorizedDataAccessException("insert failed", ex) {
                };
            }
        });
        return Boolean.TRUE.equals(inserted);
    }

    private static boolean isDuplicate(SQLException exception) {
        SQLException current = exception;
        while (current != null) {
            if (current.getErrorCode() == 1062) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    private static void nullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
