package com.ledger.error;

public final class LedgerExceptions {

    private LedgerExceptions() {
    }

    public static final class NotFoundException extends RuntimeException {
        private final ProblemBody problem;

        public NotFoundException(String pointer, String detail) {
            super(detail);
            this.problem = Problems.of(ErrorCode.NOT_FOUND, pointer, detail);
        }

        public ProblemBody problem() {
            return problem;
        }
    }

    public static final class IdempotencyConflictException extends RuntimeException {
        private final ProblemBody problem;

        public IdempotencyConflictException() {
            super(ErrorCode.IDEMPOTENCY_CONFLICT.title());
            this.problem = Problems.of(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "/Idempotency-Key",
                    "This Idempotency-Key was already used for a different request. Use a new key, or retry the original request unchanged."
            );
        }

        public ProblemBody problem() {
            return problem;
        }
    }

    public static final class RequestException extends RuntimeException {
        private final ProblemBody problem;

        public RequestException(ProblemBody problem) {
            super(problem.detail());
            this.problem = problem;
        }

        public ProblemBody problem() {
            return problem;
        }
    }
}
