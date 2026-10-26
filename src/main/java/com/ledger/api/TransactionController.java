package com.ledger.api;

import com.ledger.account.LedgerQueryService;
import com.ledger.api.ApiModels.ReversalRequest;
import com.ledger.api.ApiModels.TransactionFeed;
import com.ledger.api.ApiModels.TransactionRequest;
import com.ledger.json.LedgerJson;
import com.ledger.ledger.PostingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransactionController {

    private final PostingService posting;
    private final LedgerQueryService queries;
    private final LedgerJson json;

    public TransactionController(PostingService posting, LedgerQueryService queries, LedgerJson json) {
        this.posting = posting;
        this.queries = queries;
        this.json = json;
    }

    @PostMapping("/v1/transactions")
    public ResponseEntity<String> record(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody TransactionRequest body,
            HttpServletRequest request
    ) {
        return Responses.of(posting.record(idempotencyKey, requestId(request), body));
    }

    @GetMapping("/v1/transactions/{transactionId}")
    public ResponseEntity<String> get(@PathVariable String transactionId) {
        String body = json.write(Bodies.transaction(queries.transaction(transactionId)));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @GetMapping("/v1/transactions")
    public TransactionFeed feed(
            @RequestParam(required = false) Long afterSequence,
            @RequestParam(required = false) Integer limit
    ) {
        return queries.feed(afterSequence, limit);
    }

    @PostMapping("/v1/transactions/{transactionId}/reversals")
    public ResponseEntity<String> reverse(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String transactionId,
            @RequestBody(required = false) ReversalRequest body,
            HttpServletRequest request
    ) {
        String description = body == null ? null : body.description();
        return Responses.of(posting.reverse(idempotencyKey, requestId(request), transactionId, description));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
