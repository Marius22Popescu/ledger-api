package com.ledger.store;

public record IdempotencyRow(
        String key,
        String requestHash,
        int httpStatus,
        String responseBody,
        String location,
        boolean completed
) {
}
