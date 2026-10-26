package com.ledger.domain;

import java.util.Locale;
import java.util.Optional;

public enum LedgerCurrency {
    EUR(2),
    USD(2),
    JPY(0);

    private final int decimals;

    LedgerCurrency(int decimals) {
        this.decimals = decimals;
    }

    public int decimals() {
        return decimals;
    }

    public String code() {
        return name();
    }

    public String settlementAccountId() {
        return "settlement-" + name().toLowerCase(Locale.ROOT);
    }

    public static Optional<LedgerCurrency> fromCode(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
