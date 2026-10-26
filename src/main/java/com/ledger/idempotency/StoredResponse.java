package com.ledger.idempotency;

public record StoredResponse(int status, String body, String location, boolean replayed) {
}
