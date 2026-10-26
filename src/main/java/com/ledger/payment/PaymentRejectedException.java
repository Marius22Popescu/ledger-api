package com.ledger.payment;

import com.ledger.error.ProblemBody;

public class PaymentRejectedException extends RuntimeException {

    private final ProblemBody problem;

    public PaymentRejectedException(ProblemBody problem) {
        super(problem.detail());
        this.problem = problem;
    }

    public ProblemBody problem() {
        return problem;
    }
}
