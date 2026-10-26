package com.ledger.error;

import java.util.EnumSet;
import java.util.List;

public final class Problems {

    private Problems() {
    }

    public static ProblemBody of(ErrorCode code, String pointer, String detail) {
        return from(List.of(new FieldViolation(pointer, code, detail)));
    }

    public static ProblemBody from(List<FieldViolation> violations) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("at least one violation is required");
        }
        int status = violations.stream().mapToInt(violation -> violation.code().status()).max().orElse(400);
        EnumSet<ErrorCode> codes = EnumSet.noneOf(ErrorCode.class);
        violations.forEach(violation -> codes.add(violation.code()));
        ErrorCode primary = codes.size() == 1
                ? codes.iterator().next()
                : (status >= 500 ? ErrorCode.TEMPORARILY_UNAVAILABLE : status == 400 ? ErrorCode.INVALID_REQUEST : ErrorCode.REJECTED);
        String detail = violations.size() == 1
                ? violations.get(0).detail()
                : "The request was refused. See errors for each part that failed.";
        List<ProblemBody.ErrorItem> items = violations.stream()
                .map(violation -> new ProblemBody.ErrorItem(violation.pointer(), violation.code().name(), violation.detail()))
                .toList();
        return new ProblemBody(primary.typeUri(), primary.title(), status, primary.name(), detail, items);
    }
}
