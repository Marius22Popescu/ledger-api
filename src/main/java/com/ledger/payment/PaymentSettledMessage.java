package com.ledger.payment;

public record PaymentSettledMessage(
        String eventId,
        String paymentId,
        String merchantAccountId,
        String amount,
        String currency
) {
}
