package com.ledger.ledger;

import com.ledger.error.ProblemBody;

public sealed interface Outcome {
    record Success(int status, String body, String location) implements Outcome {
    }

    record Failure(ProblemBody problem) implements Outcome {
    }

    record AlreadyApplied() implements Outcome {
    }
}
