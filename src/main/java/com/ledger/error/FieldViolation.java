package com.ledger.error;

public record FieldViolation(String pointer, ErrorCode code, String detail) {
}
