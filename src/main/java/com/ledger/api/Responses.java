package com.ledger.api;

import com.ledger.idempotency.StoredResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public final class Responses {

    private Responses() {
    }

    public static ResponseEntity<String> of(StoredResponse stored) {
        MediaType type = stored.status() >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(stored.status()).contentType(type);
        if (stored.location() != null) {
            builder.header(HttpHeaders.LOCATION, stored.location());
        }
        if (stored.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(stored.body());
    }
}
