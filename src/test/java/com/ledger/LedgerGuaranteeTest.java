package com.ledger;

import com.ledger.api.ApiModels.AccountBody;
import com.ledger.api.ApiModels.HistoricalBalance;
import com.ledger.api.ApiModels.TransactionBody;
import com.ledger.error.ProblemBody;
import com.ledger.json.LedgerJson;
import com.ledger.messaging.BrokerNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the guarantees the ledger is evaluated on: balanced and atomic posts,
 * idempotent retries, concurrency, payment redelivery, and announcements.
 * Runs against real MySQL and RabbitMQ.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ledger.outbox.delay-ms=100",
        "spring.datasource.hikari.maximum-pool-size=32"
})
class LedgerGuaranteeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private LedgerJson json;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void ledgerStaysSolvent() {
        assertEquals(0L, unbalancedTransactions(), "a transaction was stored unbalanced");
        assertEquals(0L, currencySum("EUR"));
        assertEquals(0L, currencySum("USD"));
        assertEquals(0L, currencySum("JPY"));
    }

    @Test
    void tenthsStayExactAndHistoryDoesNotFollowTheCurrentBalance() throws Exception {
        String merchant = open("eur", "0.00");
        TransactionBody first = postOk("tenths-a-" + merchant, "First tenth",
                entry(merchant, "CREDIT", "0.10"),
                entry("settlement-eur", "DEBIT", "0.10"));
        Thread.sleep(30);
        postOk("tenths-b-" + merchant, "Second tenth",
                entry(merchant, "CREDIT", "0.20"),
                entry("settlement-eur", "DEBIT", "0.20"));

        assertEquals("0.30", account(merchant).balance());
        String at = URLEncoder.encode(first.recordedAt(), StandardCharsets.UTF_8);
        HistoricalBalance then = json.read(ok(get("/v1/accounts/" + merchant + "/balance?at=" + at)).body(), HistoricalBalance.class);
        assertEquals("0.10", then.balance());
        assertEquals("0.30", account(merchant).balance());
    }

    @Test
    void specPayoutSplitsOneDebitAcrossTwoCredits() throws Exception {
        String merchant = open("eur", "0.00");
        String payouts = open("eur", "0.00");
        String fees = open("eur", "0.00");
        long settlement = minor("settlement-eur");
        postOk("fund-" + merchant, "Customer payment",
                entry("settlement-eur", "DEBIT", "100.00"),
                entry(merchant, "CREDIT", "100.00"));
        postOk("payout-" + merchant, "Payout merchant",
                entry(merchant, "DEBIT", "100.00"),
                entry(payouts, "CREDIT", "98.50"),
                entry(fees, "CREDIT", "1.50"));

        assertEquals("0.00", account(merchant).balance());
        assertEquals("98.50", account(payouts).balance());
        assertEquals("1.50", account(fees).balance());
        assertEquals(settlement - 10_000L, minor("settlement-eur"));
    }

    @Test
    void refusedTransactionLeavesNoLedgerRows() throws Exception {
        String merchant = open("eur", "0.00");
        long transactions = scalar("SELECT COUNT(*) FROM transactions");
        long entries = scalar("SELECT COUNT(*) FROM entries");
        long outbox = scalar("SELECT COUNT(*) FROM outbox_messages");
        String before = account(merchant).balance();

        String body = transaction("This does not balance",
                entry(merchant, "DEBIT", "5.00"),
                entry("settlement-eur", "CREDIT", "4.00"));
        String key = "refuse-" + merchant;
        ApiResult first = post("/v1/transactions", key, body);
        ApiResult replay = post("/v1/transactions", key, body);

        assertEquals(422, first.status(), first.body());
        assertTrue(first.body().contains("UNBALANCED"), first.body());
        assertEquals(422, replay.status(), replay.body());
        assertEquals("true", replay.replayed());
        assertEquals(first.body(), replay.body());
        assertEquals(transactions, scalar("SELECT COUNT(*) FROM transactions"));
        assertEquals(entries, scalar("SELECT COUNT(*) FROM entries"));
        assertEquals(outbox, scalar("SELECT COUNT(*) FROM outbox_messages"));
        assertEquals(before, account(merchant).balance());
    }

    @Test
    void repeatedRequestReplaysAndADifferentBodyConflicts() throws Exception {
        String merchant = open("eur", "50.00");
        String body = transaction("Rent",
                entry(merchant, "DEBIT", "3.00"),
                entry("settlement-eur", "CREDIT", "3.00"));
        String key = "rent-" + merchant;
        ApiResult first = post("/v1/transactions", key, body);
        ApiResult second = post("/v1/transactions", key, body);
        TransactionBody posted = json.read(first.body(), TransactionBody.class);

        assertEquals(201, first.status(), first.body());
        assertEquals(201, second.status(), second.body());
        assertEquals("true", second.replayed());
        assertEquals(posted, json.read(second.body(), TransactionBody.class));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM transactions WHERE description = 'Rent'"));

        ApiResult conflict = post("/v1/transactions", key, transaction("Different rent",
                entry(merchant, "DEBIT", "3.00"),
                entry("settlement-eur", "CREDIT", "3.00")));
        assertEquals(409, conflict.status(), conflict.body());
        assertTrue(conflict.body().contains("IDEMPOTENCY_CONFLICT"), conflict.body());
        assertEquals("-3.00", account(merchant).balance());
    }

    @Test
    void onlyOneOfTwoDebitsFitsTheOverdraft() throws Exception {
        String merchant = open("eur", "10.00");
        String body = transaction("Withdraw",
                entry(merchant, "DEBIT", "10.00"),
                entry("settlement-eur", "CREDIT", "10.00"));
        List<ApiResult> results = race(List.of(
                () -> post("/v1/transactions", "left-" + merchant, body),
                () -> post("/v1/transactions", "right-" + merchant, body)
        ));

        assertEquals(1L, results.stream().filter(result -> result.status() == 201).count(), String.valueOf(results));
        assertEquals(1L, results.stream().filter(result -> result.status() == 422).count(), String.valueOf(results));
        assertTrue(results.stream().anyMatch(result -> result.body().contains("OVERDRAFT_LIMIT")), String.valueOf(results));
        assertEquals("-10.00", account(merchant).balance());
        assertEquals(1L, scalar("SELECT COUNT(*) FROM entries WHERE account_id = ? AND amount_minor = 1000", merchant));
    }

    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {
        String left = open("eur", "50.00");
        String right = open("eur", "50.00");
        List<ApiResult> results = race(List.of(
                () -> post("/v1/transactions", "ab-" + left, transaction("A to B",
                        entry(left, "DEBIT", "5.00"),
                        entry(right, "CREDIT", "5.00"))),
                () -> post("/v1/transactions", "ba-" + left, transaction("B to A",
                        entry(right, "DEBIT", "5.00"),
                        entry(left, "CREDIT", "5.00")))
        ));

        assertTrue(results.stream().allMatch(result -> result.status() == 201), String.valueOf(results));
        assertEquals("0.00", account(left).balance());
        assertEquals("0.00", account(right).balance());
    }

    @Test
    void concurrentCreditsDoNotLoseUpdates() throws Exception {
        String merchant = open("eur", "0.00");
        List<Callable<ApiResult>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String key = "credit-" + merchant + "-" + i;
            calls.add(() -> post("/v1/transactions", key, transaction(key,
                    entry("settlement-eur", "DEBIT", "1.00"),
                    entry(merchant, "CREDIT", "1.00"))));
        }
        List<ApiResult> results = race(calls);

        assertTrue(results.stream().allMatch(result -> result.status() == 201), String.valueOf(results));
        assertEquals("16.00", account(merchant).balance());
        assertEquals(16L, scalar("SELECT COUNT(*) FROM entries WHERE account_id = ? AND direction = 'CREDIT'", merchant));
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateOneTransaction() throws Exception {
        String merchant = open("eur", "20.00");
        String key = "once-" + merchant;
        String body = transaction(key,
                entry(merchant, "DEBIT", "2.00"),
                entry("settlement-eur", "CREDIT", "2.00"));
        List<ApiResult> results = race(List.of(
                () -> postRetry("/v1/transactions", key, body),
                () -> postRetry("/v1/transactions", key, body)
        ));

        assertTrue(results.stream().allMatch(result -> result.status() == 201), String.valueOf(results));
        assertEquals(results.get(0).body(), results.get(1).body());
        assertEquals(1L, scalar("SELECT COUNT(*) FROM transactions WHERE description = ?", key));
        assertEquals("-2.00", account(merchant).balance());
    }

    @Test
    void redeliveredPaymentWithANewEventIdSettlesOnce() throws Exception {
        String merchant = open("eur", "0.00");
        String paymentId = "P-" + merchant;
        publish(payment("E1-" + merchant, paymentId, merchant, "125.50", "EUR"));
        until("first settlement", () -> "125.50".equals(balanceOrNull(merchant)));

        String probe = "probe-" + paymentId;
        publish(payment("E2-" + merchant, paymentId, merchant, "125.50", "EUR"));
        publish(payment("E3-" + merchant, probe, merchant, "0.01", "EUR"));
        until("duplicate handled before the following payment", () -> "125.51".equals(balanceOrNull(merchant)));

        assertEquals(1L, scalar("SELECT COUNT(*) FROM transactions WHERE payment_id = ?", paymentId));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM transactions WHERE payment_id = ?", probe));
    }

    @Test
    void malformedPaymentIsDeadLetteredWithoutATransaction() throws Exception {
        long transactions = scalar("SELECT COUNT(*) FROM transactions");
        String raw = "{bad-" + UUID.randomUUID() + "}";
        publish(raw);
        until("malformed payment traced", () -> scalar(
                "SELECT COUNT(*) FROM payment_intake_failures WHERE raw_body = ?", raw) >= 1);
        String dead = awaitQueue(BrokerNames.PAYMENTS_DLQ, raw);

        assertEquals(raw, dead);
        assertEquals(transactions, scalar("SELECT COUNT(*) FROM transactions"));
    }

    @Test
    void conflictingPaymentRedeliveryDoesNotChangeTheLedger() throws Exception {
        String merchant = open("eur", "0.00");
        String paymentId = "C-" + merchant;
        publish(payment("E1-" + paymentId, paymentId, merchant, "10.00", "EUR"));
        until("original settlement", () -> "10.00".equals(balanceOrNull(merchant)));
        publish(payment("E2-" + paymentId, paymentId, merchant, "99.00", "EUR"));
        until("conflict traced", () -> scalar(
                "SELECT COUNT(*) FROM payment_intake_failures WHERE payment_id = ? AND reason_code = 'PAYMENT_CONFLICT'",
                paymentId) >= 1);

        assertEquals("10.00", account(merchant).balance());
        assertEquals(1L, scalar("SELECT COUNT(*) FROM transactions WHERE payment_id = ?", paymentId));
    }

    @Test
    void recordedTransactionIsAnnouncedAndRepublishedIfThePublisherRestarts() throws Exception {
        String merchant = open("eur", "0.00");
        TransactionBody posted = postOk("announce-" + merchant, "Announce me",
                entry("settlement-eur", "DEBIT", "1.25"),
                entry(merchant, "CREDIT", "1.25"));
        assertTrue(awaitQueue(BrokerNames.RECORDED_QUEUE, posted.id()).contains("\"id\":\"" + posted.id() + "\""));
        until("outbox marked published", () -> scalar(
                "SELECT COUNT(*) FROM outbox_messages WHERE transaction_id = ? AND published_at IS NOT NULL",
                posted.id()) == 1);

        jdbc.update("UPDATE outbox_messages SET published_at = NULL WHERE transaction_id = ?", posted.id());
        assertTrue(awaitQueue(BrokerNames.RECORDED_QUEUE, posted.id()).contains(posted.id()));
        assertEquals("1.25", account(merchant).balance());
    }

    @Test
    void reversalRestoresBalancesAndCannotBeRepeated() throws Exception {
        String merchant = open("eur", "0.00");
        long settlement = minor("settlement-eur");
        TransactionBody original = postOk("to-reverse-" + merchant, "To reverse",
                entry("settlement-eur", "DEBIT", "8.00"),
                entry(merchant, "CREDIT", "8.00"));
        assertEquals("8.00", account(merchant).balance());

        ApiResult reversal = post("/v1/transactions/" + original.id() + "/reversals", "rev-" + merchant,
                "{\"description\":\"undo\"}");
        TransactionBody reversing = json.read(ok(reversal).body(), TransactionBody.class);
        TransactionBody reread = json.read(ok(get("/v1/transactions/" + original.id())).body(), TransactionBody.class);

        assertEquals("0.00", account(merchant).balance());
        assertEquals(settlement, minor("settlement-eur"));
        assertEquals(original.entries(), reread.entries());
        assertEquals(reversing.id(), reread.reversedByTransactionId());
        assertEquals(original.id(), reversing.reversesTransactionId());

        ApiResult again = post("/v1/transactions/" + original.id() + "/reversals", "rev2-" + merchant, "{}");
        assertEquals(409, again.status(), again.body());
        assertTrue(again.body().contains("ALREADY_REVERSED"), again.body());

        ApiResult nested = post("/v1/transactions/" + reversing.id() + "/reversals", "rev3-" + merchant, "{}");
        assertEquals(409, nested.status(), nested.body());
        assertTrue(nested.body().contains("REVERSAL_NOT_REVERSIBLE"), nested.body());
        assertEquals("0.00", account(merchant).balance());
    }

    @Test
    void currenciesKeepTheirOwnScales() throws Exception {
        String usd = open("usd", "0.00");
        postOk("usd-" + usd, "Dollars",
                entry("settlement-usd", "DEBIT", "1.25"),
                entry(usd, "CREDIT", "1.25"));
        assertEquals("1.25", account(usd).balance());

        String yen = open("jpy", "0");
        postOk("jpy-" + yen, "Yen",
                entry("settlement-jpy", "DEBIT", "100"),
                entry(yen, "CREDIT", "100"));
        assertEquals("100", account(yen).balance());

        ApiResult fraction = post("/v1/transactions", "jpy-fraction-" + yen, transaction("Too fine",
                entry("settlement-jpy", "DEBIT", "1.5"),
                entry(yen, "CREDIT", "1.5")));
        assertEquals(422, fraction.status(), fraction.body());
        assertTrue(fraction.body().contains("INVALID_AMOUNT_SCALE"), fraction.body());
        assertEquals("100", account(yen).balance());

        ApiResult gbp = post("/v1/accounts", "gbp-" + yen,
                "{\"id\":\"" + id("gbp") + "\",\"currency\":\"GBP\",\"overdraftLimit\":\"0.00\"}");
        assertEquals(422, gbp.status(), gbp.body());
        assertTrue(gbp.body().contains("UNSUPPORTED_CURRENCY"), gbp.body());

        ApiResult euro = post("/v1/accounts", "euro-" + yen,
                "{\"id\":\"" + id("euro") + "\",\"currency\":\"EURO\",\"overdraftLimit\":\"0\"}");
        assertEquals(400, euro.status(), euro.body());
        assertTrue(euro.body().contains("INVALID_CURRENCY"), euro.body());
    }

    private String open(String currency, String overdraft) throws Exception {
        String accountId = id(currency.toLowerCase());
        ApiResult result = post("/v1/accounts", "open-" + accountId,
                "{\"id\":\"" + accountId + "\",\"currency\":\"" + currency.toUpperCase()
                        + "\",\"overdraftLimit\":\"" + overdraft + "\"}");
        assertEquals(201, result.status(), result.body());
        return accountId;
    }

    private TransactionBody postOk(String key, String description, String[]... entries) throws Exception {
        return json.read(ok(post("/v1/transactions", key, transaction(description, entries))).body(), TransactionBody.class);
    }

    private ApiResult postRetry(String path, String key, String body) throws Exception {
        ApiResult result = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            result = post(path, key, body);
            if (result.status() != 503) {
                return result;
            }
            Thread.sleep(40L * (attempt + 1));
        }
        return result;
    }

    private ApiResult post(String path, String key, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(root() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json, application/problem+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return exchange(builder.build());
    }

    private ApiResult get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(root() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        return exchange(request);
    }

    private ApiResult exchange(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new ApiResult(
                response.statusCode(),
                response.body(),
                response.headers().firstValue("Idempotent-Replayed").orElse(null),
                response.headers().firstValue("Location").orElse(null)
        );
    }

    private List<ApiResult> race(List<Callable<ApiResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ApiResult>> futures = new ArrayList<>();
            for (Callable<ApiResult> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("race did not start");
                    }
                    return call.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<ApiResult> results = new ArrayList<>();
            for (Future<ApiResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void publish(String body) {
        rabbitTemplate.send(
                BrokerNames.PAYMENTS_EXCHANGE,
                BrokerNames.PAYMENT_SETTLED_ROUTING_KEY,
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .build()
        );
    }

    private String awaitQueue(String queue, String needle) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Message message = rabbitTemplate.receive(queue, 200);
            if (message == null) {
                continue;
            }
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (body.contains(needle)) {
                return body;
            }
        }
        fail("timed out waiting for " + needle + " on " + queue);
        return "";
    }

    private void until(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                fail(what);
            }
        }
        fail("timed out: " + what);
    }

    private AccountBody account(String accountId) throws Exception {
        return json.read(ok(get("/v1/accounts/" + accountId)).body(), AccountBody.class);
    }

    private String balanceOrNull(String accountId) {
        try {
            return account(accountId).balance();
        } catch (Exception ex) {
            return null;
        }
    }

    private static ApiResult ok(ApiResult result) {
        assertTrue(result.status() >= 200 && result.status() < 300, result.status() + " " + result.body());
        return result;
    }

    private long scalar(String sql, Object... args) {
        Number value = jdbc.queryForObject(sql, Number.class, args);
        return value == null ? 0L : value.longValue();
    }

    private long minor(String accountId) {
        return scalar("SELECT balance_minor FROM accounts WHERE id = ?", accountId);
    }

    private long currencySum(String currency) {
        return scalar("SELECT COALESCE(SUM(balance_minor), 0) FROM accounts WHERE currency = ?", currency);
    }

    private long unbalancedTransactions() {
        return scalar("""
                SELECT COUNT(*) FROM (
                    SELECT transaction_id
                    FROM entries
                    GROUP BY transaction_id
                    HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0
                ) unbalanced
                """);
    }

    private String root() {
        return "http://127.0.0.1:" + port;
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String[] entry(String accountId, String direction, String amount) {
        return new String[] {accountId, direction, amount};
    }

    private static String transaction(String description, String[]... entries) {
        StringBuilder body = new StringBuilder();
        body.append("{\"description\":").append(quote(description)).append(",\"entries\":[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"accountId\":").append(quote(entries[i][0]))
                    .append(",\"direction\":").append(quote(entries[i][1]))
                    .append(",\"amount\":").append(quote(entries[i][2]))
                    .append('}');
        }
        return body.append("]}").toString();
    }

    private static String payment(String eventId, String paymentId, String merchant, String amount, String currency) {
        return "{\"eventId\":" + quote(eventId)
                + ",\"paymentId\":" + quote(paymentId)
                + ",\"merchantAccountId\":" + quote(merchant)
                + ",\"amount\":" + quote(amount)
                + ",\"currency\":" + quote(currency) + "}";
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private record ApiResult(int status, String body, String replayed, String location) {
        @Override
        public String toString() {
            return status + " replayed=" + replayed + " " + body;
        }
    }
}
