package com.ledger.store;

import com.ledger.domain.LedgerCurrency;

import java.time.Instant;
import java.util.List;

public record StoredTransaction(
        String id,
        long sequence,
        String description,
        LedgerCurrency currency,
        Instant recordedAt,
        String reversesTransactionId,
        String reversedByTransactionId,
        String paymentId,
        String requestId,
        List<StoredEntry> entries
) {
}
