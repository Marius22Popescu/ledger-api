package com.ledger.domain;

import java.time.Instant;

public record Account(
        String id,
        LedgerCurrency currency,
        Long overdraftLimitMinor,
        long balanceMinor,
        Instant createdAt
) {
    public boolean unlimitedOverdraft() {
        return overdraftLimitMinor == null;
    }
}
