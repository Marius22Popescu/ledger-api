package com.ledger.payment;

import com.ledger.error.ErrorCode;
import com.ledger.error.Problems;
import com.ledger.json.LedgerJson;
import com.ledger.messaging.BrokerNames;
import com.ledger.store.LedgerStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

@Component
public class PaymentSettledListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettledListener.class);

    private final LedgerJson json;
    private final PaymentIntakeService intake;
    private final LedgerStore store;
    private final org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
    private final TransactionTemplate failures;
    private final Clock clock;

    public PaymentSettledListener(
            LedgerJson json,
            PaymentIntakeService intake,
            LedgerStore store,
            org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate,
            PlatformTransactionManager manager,
            Clock clock
    ) {
        this.json = json;
        this.intake = intake;
        this.store = store;
        this.rabbitTemplate = rabbitTemplate;
        this.failures = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @RabbitListener(queues = BrokerNames.PAYMENTS_QUEUE)
    public void onMessage(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        PaymentSettledMessage event;
        try {
            event = json.read(body, PaymentSettledMessage.class);
            validate(event);
        } catch (PaymentRejectedException ex) {
            traceAndDeadLetter(null, null, ex, body);
            return;
        } catch (RuntimeException ex) {
            traceAndDeadLetter(null, null,
                    new PaymentRejectedException(Problems.of(ErrorCode.INVALID_REQUEST, "/", "Payment message is not valid JSON.")),
                    body);
            return;
        }
        MDC.put("requestId", event.eventId());
        MDC.put("paymentId", event.paymentId());
        try {
            intake.accept(event);
            log.info("payment.recorded paymentId={} eventId={} merchant={}", event.paymentId(), event.eventId(), event.merchantAccountId());
        } catch (PaymentRejectedException ex) {
            traceAndDeadLetter(event.eventId(), event.paymentId(), ex, body);
        } catch (TransientDataAccessException ex) {
            throw ex;
        } finally {
            MDC.remove("requestId");
            MDC.remove("paymentId");
        }
    }

    private static void validate(PaymentSettledMessage event) {
        if (event == null
                || blank(event.eventId())
                || blank(event.paymentId())
                || blank(event.merchantAccountId())
                || blank(event.amount())
                || blank(event.currency())) {
            throw new PaymentRejectedException(Problems.of(ErrorCode.INVALID_REQUEST, "/",
                    "A payment-settled message needs eventId, paymentId, merchantAccountId, amount and currency."));
        }
        if (event.eventId().trim().length() > 128 || event.paymentId().trim().length() > 128) {
            throw new PaymentRejectedException(Problems.of(ErrorCode.INVALID_REQUEST, "/",
                    "eventId and paymentId must be at most 128 characters."));
        }
    }

    private void traceAndDeadLetter(String eventId, String paymentId, PaymentRejectedException ex, String body) {
        log.warn("payment.rejected eventId={} paymentId={} code={} detail={}", eventId, paymentId, ex.problem().code(), ex.problem().detail());
        failures.executeWithoutResult(status -> store.insertFailure(
                eventId,
                paymentId,
                ex.problem().code(),
                ex.problem().detail(),
                body,
                clock.instant()
        ));
        rabbitTemplate.send(
                BrokerNames.PAYMENTS_DLX,
                "",
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setHeader("X-Ledger-Reason", ex.problem().code())
                        .build()
        );
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
