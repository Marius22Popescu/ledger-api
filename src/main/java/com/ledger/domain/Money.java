package com.ledger.domain;

import java.math.BigDecimal;

/**
 * Amounts are integer minor units. EUR 0.10 and EUR 0.20 add as 10 + 20 = 30,
 * which formats back to 0.30. Nothing in the ledger uses binary floating point.
 */
public final class Money {

    public enum Problem {
        FORMAT,
        NOT_POSITIVE,
        SCALE,
        OVERFLOW
    }

    public static final class InvalidAmountException extends RuntimeException {
        private final Problem problem;

        public InvalidAmountException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    private Money() {
    }

    public static long toMinorUnits(String raw, int decimals) {
        return parse(raw, decimals, false);
    }

    public static long toNonNegativeMinorUnits(String raw, int decimals) {
        return parse(raw, decimals, true);
    }

    public static String format(long minor, int decimals) {
        return BigDecimal.valueOf(minor, decimals).toPlainString();
    }

    private static long parse(String raw, int decimals, boolean zeroAllowed) {
        if (raw == null || !raw.trim().matches("[0-9]+(\\.[0-9]+)?")) {
            throw new InvalidAmountException(Problem.FORMAT);
        }
        BigDecimal value = new BigDecimal(raw.trim());
        if (value.signum() < 0 || (!zeroAllowed && value.signum() == 0)) {
            throw new InvalidAmountException(Problem.NOT_POSITIVE);
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        if (stripped.scale() > decimals) {
            throw new InvalidAmountException(Problem.SCALE);
        }
        try {
            return stripped.movePointRight(decimals).longValueExact();
        } catch (ArithmeticException ex) {
            throw new InvalidAmountException(Problem.OVERFLOW);
        }
    }
}
