package com.ledger.messaging;

public final class BrokerNames {

    public static final String PAYMENTS_EXCHANGE = "payments";
    public static final String PAYMENT_SETTLED_ROUTING_KEY = "payment.settled";
    public static final String PAYMENTS_QUEUE = "payments.settled";
    public static final String PAYMENTS_DLX = "payments.dlx";
    public static final String PAYMENTS_DLQ = "payments.settled.dlq";

    public static final String LEDGER_EXCHANGE = "ledger.transactions";
    public static final String TRANSACTION_RECORDED_ROUTING_KEY = "transaction.recorded";
    public static final String RECORDED_QUEUE = "ledger.transactions.recorded";

    private BrokerNames() {
    }
}
