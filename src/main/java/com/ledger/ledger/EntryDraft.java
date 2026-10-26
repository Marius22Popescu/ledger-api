package com.ledger.ledger;

public record EntryDraft(String accountId, String direction, String amount, String pointer) {
}
