package com.ledger.api;

import com.ledger.domain.Account;
import com.ledger.domain.Money;
import com.ledger.store.StatementLine;
import com.ledger.store.StoredEntry;
import com.ledger.store.StoredTransaction;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class Bodies {

    private Bodies() {
    }

    public static String time(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MICROS));
    }

    public static ApiModels.AccountBody account(Account account) {
        int decimals = account.currency().decimals();
        String overdraft = account.overdraftLimitMinor() == null
                ? null
                : Money.format(account.overdraftLimitMinor(), decimals);
        return new ApiModels.AccountBody(
                account.id(),
                account.currency().code(),
                overdraft,
                Money.format(account.balanceMinor(), decimals),
                time(account.createdAt())
        );
    }

    public static ApiModels.TransactionBody transaction(StoredTransaction stored) {
        int decimals = stored.currency().decimals();
        List<ApiModels.TransactionEntryBody> entries = stored.entries().stream()
                .map(entry -> entry(entry, decimals))
                .toList();
        return new ApiModels.TransactionBody(
                stored.id(),
                stored.sequence(),
                stored.description(),
                stored.currency().code(),
                time(stored.recordedAt()),
                stored.reversesTransactionId(),
                stored.reversedByTransactionId(),
                stored.paymentId(),
                stored.requestId(),
                entries
        );
    }

    public static ApiModels.StatementItem statementItem(StatementLine line) {
        int decimals = line.currency().decimals();
        return new ApiModels.StatementItem(
                line.entryId(),
                line.transactionId(),
                line.direction().name(),
                Money.format(line.amountMinor(), decimals),
                Money.format(line.balanceAfterMinor(), decimals),
                time(line.recordedAt()),
                line.description()
        );
    }

    private static ApiModels.TransactionEntryBody entry(StoredEntry entry, int decimals) {
        return new ApiModels.TransactionEntryBody(
                entry.accountId(),
                entry.direction().name(),
                Money.format(entry.amountMinor(), decimals)
        );
    }
}
