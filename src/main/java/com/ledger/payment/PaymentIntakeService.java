package com.ledger.payment;

import com.ledger.domain.Iso4217;
import com.ledger.domain.LedgerCurrency;
import com.ledger.error.ErrorCode;
import com.ledger.error.Problems;
import com.ledger.ledger.EntryDraft;
import com.ledger.ledger.Outcome;
import com.ledger.ledger.PostCommand;
import com.ledger.ledger.PostingService;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;

@Service
public class PaymentIntakeService {

    private final PostingService posting;
    private final TransactionTemplate transactions;

    public PaymentIntakeService(PostingService posting, PlatformTransactionManager manager) {
        this.posting = posting;
        this.transactions = new TransactionTemplate(manager);
    }

    public void accept(PaymentSettledMessage message) {
        for (int attempt = 1; ; attempt++) {
            try {
                transactions.executeWithoutResult(status -> apply(message));
                return;
            } catch (TransientDataAccessException ex) {
                if (attempt >= 5) {
                    throw ex;
                }
            }
        }
    }

    private void apply(PaymentSettledMessage message) {
        String currencyCode = message.currency() == null ? "" : message.currency().trim().toUpperCase(Locale.ROOT);
        if (!currencyCode.matches("[A-Z]{3}") || !Iso4217.isCurrencyCode(currencyCode)) {
            throw new PaymentRejectedException(Problems.of(ErrorCode.INVALID_CURRENCY, "/currency",
                    "\"" + message.currency() + "\" is not an ISO 4217 currency code."));
        }
        LedgerCurrency currency = LedgerCurrency.fromCode(currencyCode).orElse(null);
        if (currency == null) {
            throw new PaymentRejectedException(Problems.of(ErrorCode.UNSUPPORTED_CURRENCY, "/currency",
                    currencyCode + " is a valid ISO 4217 currency code, but this ledger supports only EUR, USD and JPY."));
        }
        PostCommand command = new PostCommand(
                "Payment " + message.paymentId().trim() + " settled",
                List.of(
                        new EntryDraft(currency.settlementAccountId(), "DEBIT", message.amount(), "/entries/0"),
                        new EntryDraft(message.merchantAccountId(), "CREDIT", message.amount(), "/entries/1")
                ),
                null,
                message.paymentId().trim(),
                message.eventId()
        );
        Outcome outcome = posting.post(command);
        if (outcome instanceof Outcome.Failure failure) {
            throw new PaymentRejectedException(failure.problem());
        }
    }
}
