package com.ledger.error;

public enum ErrorCode {
    INVALID_REQUEST(400, "The request is invalid"),
    INVALID_CURRENCY(400, "Not an ISO 4217 currency code"),
    INVALID_AMOUNT(400, "Amount must be a positive decimal"),
    IDEMPOTENCY_KEY_REQUIRED(400, "Idempotency-Key is required"),
    NOT_FOUND(404, "Resource not found"),
    ACCOUNT_EXISTS(409, "Account already exists"),
    ALREADY_REVERSED(409, "Transaction has already been reversed"),
    REVERSAL_NOT_REVERSIBLE(409, "A reversal cannot be reversed"),
    IDEMPOTENCY_CONFLICT(409, "This Idempotency-Key was used for a different request"),
    REJECTED(422, "The transaction was refused"),
    UNSUPPORTED_CURRENCY(422, "This currency is not supported"),
    UNKNOWN_ACCOUNT(422, "Unknown account"),
    UNBALANCED(422, "Debits and credits don't balance"),
    OVERDRAFT_LIMIT(422, "Account would pass its overdraft limit"),
    CURRENCY_MISMATCH(422, "Accounts are in different currencies"),
    TOO_FEW_ENTRIES(422, "A transaction needs at least two entries"),
    TOO_MANY_ENTRIES(422, "A transaction has too many entries"),
    INVALID_AMOUNT_SCALE(422, "Amount has too many decimal places"),
    PAYMENT_CONFLICT(422, "This payment id was already recorded with different details"),
    INTERNAL(500, "The ledger failed to process the request"),
    BROKER_UNAVAILABLE(503, "The message broker did not accept the message"),
    TEMPORARILY_UNAVAILABLE(503, "The ledger could not finish the request, retry it");

    private final int status;
    private final String title;

    ErrorCode(int status, String title) {
        this.status = status;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return "urn:ledger:problem:" + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
