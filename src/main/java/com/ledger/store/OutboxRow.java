package com.ledger.store;

public record OutboxRow(long id, String transactionId, String payload) {
}
