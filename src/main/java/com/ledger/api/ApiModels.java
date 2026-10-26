package com.ledger.api;

import java.util.List;

public final class ApiModels {

    private ApiModels() {
    }

    public record OpenAccountRequest(String id, String currency, String overdraftLimit) {
    }

    public record AccountBody(
            String id,
            String currency,
            String overdraftLimit,
            String balance,
            String createdAt
    ) {
    }

    public record EntryRequest(String accountId, String direction, String amount) {
    }

    public record TransactionRequest(String description, List<EntryRequest> entries) {
    }

    public record TransactionEntryBody(String accountId, String direction, String amount) {
    }

    public record TransactionBody(
            String id,
            long sequence,
            String description,
            String currency,
            String recordedAt,
            String reversesTransactionId,
            String reversedByTransactionId,
            String paymentId,
            String requestId,
            List<TransactionEntryBody> entries
    ) {
    }

    public record ReversalRequest(String description) {
    }

    public record StatementItem(
            long entryId,
            String transactionId,
            String direction,
            String amount,
            String balanceAfter,
            String recordedAt,
            String description
    ) {
    }

    public record StatementPage(String accountId, List<StatementItem> items, String nextCursor) {
    }

    public record HistoricalBalance(String accountId, String currency, String balance, String at) {
    }

    public record TransactionFeed(List<TransactionBody> items, Long nextSequence) {
    }
}
