package com.ledger.ledger;

import java.util.List;

public record PostCommand(
        String description,
        List<EntryDraft> entries,
        String reversesTransactionId,
        String paymentId,
        String requestId
) {
}
