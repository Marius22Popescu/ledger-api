package com.ledger.store;

import com.ledger.domain.Direction;

public record StoredEntry(
        long id,
        String accountId,
        Direction direction,
        long amountMinor,
        long balanceAfterMinor,
        int ordinal
) {
}
