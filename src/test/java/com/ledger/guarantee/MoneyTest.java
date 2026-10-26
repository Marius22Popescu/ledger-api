package com.ledger.guarantee;

import com.ledger.domain.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void tenthsAddAsMinorUnits() {
        long ten = Money.toMinorUnits("0.10", 2);
        long twenty = Money.toMinorUnits("0.20", 2);
        assertEquals("0.30", Money.format(ten + twenty, 2));
    }

    @Test
    void trailingZerosDoNotChangeTheMinorAmount() {
        assertEquals(Money.toMinorUnits("10", 2), Money.toMinorUnits("10.00", 2));
        assertEquals("100", Money.format(Money.toMinorUnits("100", 0), 0));
    }

    @Test
    void rejectsScaleZeroAndNonDecimals() {
        assertEquals(Money.Problem.SCALE, problem("0.001", 2, false));
        assertEquals(Money.Problem.SCALE, problem("1.5", 0, false));
        assertEquals(Money.Problem.NOT_POSITIVE, problem("0", 2, false));
        assertEquals(Money.Problem.NOT_POSITIVE, problem("0.00", 2, false));
        assertEquals(0L, Money.toNonNegativeMinorUnits("0.00", 2));
        assertEquals(Money.Problem.FORMAT, problem("-1.00", 2, false));
        assertEquals(Money.Problem.FORMAT, problem("1e2", 2, false));
        assertEquals(Money.Problem.FORMAT, problem("", 2, false));
    }

    @Test
    void rejectsAmountsThatDoNotFitInALong() {
        assertThrows(Money.InvalidAmountException.class, () -> Money.toMinorUnits("92233720368547758.08", 2));
    }

    private static Money.Problem problem(String raw, int decimals, boolean zeroAllowed) {
        try {
            if (zeroAllowed) {
                Money.toNonNegativeMinorUnits(raw, decimals);
            } else {
                Money.toMinorUnits(raw, decimals);
            }
            throw new AssertionError("expected rejection of " + raw);
        } catch (Money.InvalidAmountException ex) {
            return ex.problem();
        }
    }
}
