package com.ledger.api;

import com.ledger.error.ErrorCode;
import com.ledger.error.Problems;
import com.ledger.json.LedgerJson;
import com.ledger.messaging.BrokerNames;
import com.ledger.payment.PaymentSettledMessage;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@ConditionalOnProperty(name = "ledger.samples.enabled", havingValue = "true", matchIfMissing = true)
public class SamplePaymentController {

    private final RabbitTemplate rabbitTemplate;
    private final LedgerJson json;

    public SamplePaymentController(RabbitTemplate rabbitTemplate, LedgerJson json) {
        this.rabbitTemplate = rabbitTemplate;
        this.json = json;
    }

    @PostMapping("/v1/samples/payment-settled")
    public ResponseEntity<String> publish(@RequestBody PaymentSettledMessage message) {
        if (message == null
                || blank(message.eventId())
                || blank(message.paymentId())
                || blank(message.merchantAccountId())
                || blank(message.amount())
                || blank(message.currency())) {
            var problem = Problems.of(ErrorCode.INVALID_REQUEST, "/",
                    "A sample payment needs eventId, paymentId, merchantAccountId, amount and currency.");
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(json.write(problem));
        }
        String body = json.write(message);
        try {
            rabbitTemplate.invoke(operations -> {
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setMessageId(message.eventId());
                operations.send(
                        BrokerNames.PAYMENTS_EXCHANGE,
                        BrokerNames.PAYMENT_SETTLED_ROUTING_KEY,
                        new Message(body.getBytes(StandardCharsets.UTF_8), properties)
                );
                operations.waitForConfirmsOrDie(2_000);
                return null;
            });
        } catch (RuntimeException ex) {
            var problem = Problems.of(ErrorCode.BROKER_UNAVAILABLE, "/", "The message broker did not accept the payment message.");
            return ResponseEntity.status(503).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(json.write(problem));
        }
        return ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
