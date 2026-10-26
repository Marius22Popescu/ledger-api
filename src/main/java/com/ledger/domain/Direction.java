package com.ledger.domain;

import java.util.Locale;
import java.util.Optional;

public enum Direction {
    DEBIT,
    CREDIT;

    public Direction flipped() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    public static Optional<Direction> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
