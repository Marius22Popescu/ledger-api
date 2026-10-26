package com.ledger.guarantee;

import com.ledger.domain.Iso4217;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Iso4217Test {

    @Test
    void recognisesRealCodesIncludingOnesTheLedgerDoesNotPost() {
        assertTrue(Iso4217.isCurrencyCode("EUR"));
        assertTrue(Iso4217.isCurrencyCode("USD"));
        assertTrue(Iso4217.isCurrencyCode("JPY"));
        assertTrue(Iso4217.isCurrencyCode("GBP"));
        assertFalse(Iso4217.isCurrencyCode("EURO"));
        assertFalse(Iso4217.isCurrencyCode("ABC"));
        assertFalse(Iso4217.isCurrencyCode("eur"));
    }
}
