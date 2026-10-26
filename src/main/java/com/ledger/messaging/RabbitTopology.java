package com.ledger.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopology {

    @Bean
    TopicExchange paymentsExchange() {
        return new TopicExchange(BrokerNames.PAYMENTS_EXCHANGE, true, false);
    }

    @Bean
    Queue paymentsQueue() {
        return QueueBuilder.durable(BrokerNames.PAYMENTS_QUEUE).build();
    }

    @Bean
    Binding paymentsBinding(Queue paymentsQueue, TopicExchange paymentsExchange) {
        return BindingBuilder.bind(paymentsQueue).to(paymentsExchange).with(BrokerNames.PAYMENT_SETTLED_ROUTING_KEY);
    }

    @Bean
    FanoutExchange paymentsDlx() {
        return new FanoutExchange(BrokerNames.PAYMENTS_DLX, true, false);
    }

    @Bean
    Queue paymentsDlq() {
        return QueueBuilder.durable(BrokerNames.PAYMENTS_DLQ).build();
    }

    @Bean
    Binding paymentsDlqBinding(Queue paymentsDlq, FanoutExchange paymentsDlx) {
        return BindingBuilder.bind(paymentsDlq).to(paymentsDlx);
    }

    @Bean
    TopicExchange ledgerExchange() {
        return new TopicExchange(BrokerNames.LEDGER_EXCHANGE, true, false);
    }

    @Bean
    Queue recordedQueue() {
        return QueueBuilder.durable(BrokerNames.RECORDED_QUEUE).build();
    }

    @Bean
    Binding recordedBinding(Queue recordedQueue, TopicExchange ledgerExchange) {
        return BindingBuilder.bind(recordedQueue).to(ledgerExchange).with(BrokerNames.TRANSACTION_RECORDED_ROUTING_KEY);
    }
}
