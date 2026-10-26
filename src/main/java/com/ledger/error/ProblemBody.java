package com.ledger.error;

import java.util.List;

public record ProblemBody(
        String type,
        String title,
        int status,
        String code,
        String detail,
        List<ErrorItem> errors
) {
    public record ErrorItem(String pointer, String code, String detail) {
    }
}
