package com.ledger.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ledger.store.LedgerStore;
import com.ledger.store.OutboxRow;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(2);

    private final LedgerStore store;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OutboxPublisher(
            LedgerStore store,
            RabbitTemplate rabbitTemplate,
            PlatformTransactionManager manager,
            Clock clock
    ) {
        this.store = store;
        this.rabbitTemplate = rabbitTemplate;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.delay-ms:400}")
    public void publish() {
        try {
            Instant now = clock.instant();
            List<OutboxRow> rows = transactions.execute(status -> {
                List<OutboxRow> claimed = store.lockUnpublished(now.minus(CLAIM_LEASE), 20);
                if (!claimed.isEmpty()) {
                    store.markClaimed(claimed.stream().map(OutboxRow::id).toList(), now);
                }
                return claimed;
            });
            if (rows == null || rows.isEmpty()) {
                return;
            }
            rabbitTemplate.invoke(operations -> {
                for (OutboxRow row : rows) {
                    MessageProperties properties = new MessageProperties();
                    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                    properties.setMessageId(row.transactionId());
                    operations.send(
                            BrokerNames.LEDGER_EXCHANGE,
                            BrokerNames.TRANSACTION_RECORDED_ROUTING_KEY,
                            new Message(row.payload().getBytes(StandardCharsets.UTF_8), properties)
                    );
                }
                operations.waitForConfirmsOrDie(2_000);
                return null;
            });
            transactions.executeWithoutResult(status ->
                    store.markPublished(rows.stream().map(OutboxRow::id).toList(), clock.instant()));
        } catch (RuntimeException ex) {
            log.warn("outbox.publish failed: {}", ex.toString());
        }
    }
}
