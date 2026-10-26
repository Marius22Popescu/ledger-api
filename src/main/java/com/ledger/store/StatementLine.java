package com.ledger.store;

import com.ledger.domain.Direction;
import com.ledger.domain.LedgerCurrency;

import java.time.Instant;

public record StatementLine(
        long entryId,
        String transactionId,
        Direction direction,
        long amountMinor,
        long balanceAfterMinor,
        Instant recordedAt,
        String description,
        LedgerCurrency currency
) {
}
