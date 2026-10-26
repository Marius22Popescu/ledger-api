package com.ledger.api;

import com.ledger.account.AccountService;
import com.ledger.account.LedgerQueryService;
import com.ledger.api.ApiModels.AccountBody;
import com.ledger.api.ApiModels.HistoricalBalance;
import com.ledger.api.ApiModels.OpenAccountRequest;
import com.ledger.api.ApiModels.StatementPage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    private final AccountService accounts;
    private final LedgerQueryService queries;

    public AccountController(AccountService accounts, LedgerQueryService queries) {
        this.accounts = accounts;
        this.queries = queries;
    }

    @PostMapping("/v1/accounts")
    public ResponseEntity<String> open(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody OpenAccountRequest body
    ) {
        return Responses.of(accounts.open(idempotencyKey, body));
    }

    @GetMapping("/v1/accounts/{accountId}")
    public AccountBody get(@PathVariable String accountId) {
        return Bodies.account(queries.account(accountId));
    }

    @GetMapping("/v1/accounts/{accountId}/entries")
    public StatementPage entries(
            @PathVariable String accountId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit
    ) {
        return queries.statement(accountId, cursor, limit);
    }

    @GetMapping("/v1/accounts/{accountId}/balance")
    public HistoricalBalance balance(@PathVariable String accountId, @RequestParam("at") String at) {
        return queries.balanceAt(accountId, at);
    }
}
