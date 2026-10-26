# Ledger API

Double-entry ledger for EUR, USD and JPY. Accounts, transactions and reversals are HTTP resources. Settled payments arrive as messages. Every recorded transaction is announced on a broker.

Amounts are decimal strings on the wire and integer minor units in the database. `0.10 + 0.20` is `10 + 20 = 30`, which is returned as `0.30`.

## Architecture

One Spring Boot process.

- `domain` holds currency, direction and money parsing. No I/O.
- `ledger` decides whether a post is balanced, within overdraft, and reversible, then writes it.
- `store` is JDBC. It locks rows and inserts. It does not decide business outcomes.
- `idempotency` runs a write and its idempotency record in one database transaction.
- `payment` consumes `payment.settled` and posts through the same ledger path.
- `messaging` declares exchanges and publishes the outbox.
- `api` maps HTTP onto those services and does not calculate balances.

MySQL is the source of truth. RabbitMQ delivers payments in and transaction announcements out. There is no in-memory lock, so a second instance is correct as long as it uses the same database.

### Concurrency

Before a post changes balances it locks every involved account with `SELECT ... FOR UPDATE`, one account at a time, in account-id order. A transfer A→B and a transfer B→A take the locks in the same order, so they cannot deadlock each other.

The overdraft check uses the balance under that lock, after the whole transaction's net effect, not an intermediate entry. Two debits that would each fit, but not together, cannot both commit.

### Idempotency

`POST /v1/accounts`, `POST /v1/transactions` and `POST /v1/transactions/{id}/reversals` require `Idempotency-Key`.

The key and a SHA-256 of the canonical request are inserted in the same database transaction as the ledger write. A commit stores both, or a crash stores neither, so a retry can still succeed. The same key and the same request return the stored status and body, with `Idempotent-Replayed: true`. The same key and a different request return `409 IDEMPOTENCY_CONFLICT`. A refused post is stored too, so a retry of a refusal does not get a second chance to insert rows.

Payments are not deduplicated by the HTTP key. They are deduplicated by `paymentId`.

### Outbox

The transaction row, its entries, the balance update and the outbox row commit together. A scheduler claims a batch in a short transaction (`claimed_at`, with `FOR UPDATE SKIP LOCKED`), publishes after that transaction commits, and sets `published_at` only after RabbitMQ confirms. Row locks are not held while waiting for the confirm. A claim older than two seconds can be taken again, so a crash after the broker has the message and before the mark sends the event again. Announcements are at-least-once. Consumers must tolerate a repeated `transactionId`.

## API

The versioned prefix is `/v1`. The contract is `src/main/resources/openapi.yaml`.

| Method | Path | Role |
| --- | --- | --- |
| POST | `/v1/accounts` | Open an account. Omitted `overdraftLimit` means `0`. |
| GET | `/v1/accounts/{id}` | Current balance. |
| GET | `/v1/accounts/{id}/entries` | Statement, with the balance after each entry. |
| GET | `/v1/accounts/{id}/balance?at=` | Balance at an ISO-8601 instant. |
| POST | `/v1/transactions` | Record a balanced transaction. |
| GET | `/v1/transactions/{id}` | Read a transaction. Entries are never rewritten. |
| GET | `/v1/transactions?afterSequence=` | Feed in commit order. |
| POST | `/v1/transactions/{id}/reversals` | Append the opposite entries once. |
| POST | `/v1/samples/payment-settled` | Publish a sample payment. Does not record it itself. |

A credit increases a balance. A debit decreases it. The seeded accounts `settlement-eur`, `settlement-usd` and `settlement-jpy` have no overdraft limit, so customer funds can be credited from them.

Errors are `application/problem+json`: `type`, `title`, `status`, `code`, `detail`, and `errors[]` with a JSON pointer. Examples: `UNBALANCED` and `OVERDRAFT_LIMIT` are 422, `IDEMPOTENCY_CONFLICT` and `ALREADY_REVERSED` are 409, a currency that is not ISO 4217 is 400, GBP is ISO 4217 but `UNSUPPORTED_CURRENCY` (422).

Successful writes return the resource. The response body is what a replay returns later, so callers can treat the first response and the retry as the same result.

## Running

Java 17 and Docker are enough. MySQL and RabbitMQ do not need to be installed on the host.

```bash
./run.sh
```

That builds the API image, starts MySQL, RabbitMQ and the API, then runs the tests. The script's exit code is the test exit code.

The console is served by the API at <http://localhost:8080/>. RabbitMQ management is at <http://localhost:15672/> (`guest` / `guest`).

Stop the stack with `docker compose down`.

To run only the unit tests, without Docker:

```bash
./mvnw -B -Dtest=MoneyTest,Iso4217Test test
```

`LedgerGuaranteeTest` starts its own MySQL 8.4 and RabbitMQ 4 through Testcontainers, so `./mvnw test` also needs a working Docker daemon. Those containers are separate from the Compose stack, which keeps a test from writing into the database you are clicking through in the console.

## Docker

`docker compose up --build` starts:

- MySQL 8.4, database `ledger`, user `ledger`, session time zone UTC
- RabbitMQ 4
- the API on port 8080, after both dependencies are healthy

The image is a two-stage build: JDK 17 runs `./mvnw package`, JRE 17 runs the jar.

## Database

Flyway applies `src/main/resources/db/migration/V1__schema.sql` on startup.

- `accounts` stores `balance_minor` and a nullable `overdraft_limit_minor`. Null means unlimited.
- `transactions` are insert-only. `sequence_no` is an auto-increment. `payment_id` is unique. `reverses_transaction_id` is unique, so a transaction can be reversed once.
- `entries` store a positive `amount_minor`, a direction, and `balance_after_minor`.
- `idempotency_records` stores the key, the request hash, and the response.
- `outbox_messages` stores the announcement payload, a `claimed_at` lease, and `published_at` once the broker has confirmed.
- `payment_intake_failures` records messages that were rejected and dead-lettered.

There is no update of an entry amount and no delete of a transaction.

## Messaging

Input: topic exchange `payments`, routing key `payment.settled`, queue `payments.settled`.

A settled payment debits the settlement account and credits the merchant. The same `paymentId` with the same merchant and amount is ignored, even when `eventId` changes. The same `paymentId` with different details is rejected. A malformed message, an unknown account, an unsupported currency, or an overdraft refusal is written to `payment_intake_failures`, copied to the fanout `payments.dlx` / queue `payments.settled.dlq`, and acknowledged so the queue keeps moving. A database outage is not acknowledged; the broker redelivers.

Output: topic exchange `ledger.transactions`, routing key `transaction.recorded`, queue `ledger.transactions.recorded`. The payload is the transaction JSON. `sequence` is monotonic per database and is not gap-free.

## Consistency guarantees

- **Atomicity.** Entries, balances and the outbox row commit in one transaction. A refused post inserts none of them. The test `refusedTransactionLeavesNoLedgerRows` counts the tables before and after an unbalanced post, including the replay of that refusal.
- **Balance.** Debits and credits of a transaction are equal before insert. `AfterEach` in the guarantee suite fails if any stored transaction is unbalanced or if any currency's balances no longer sum to zero.
- **Immutability.** A reversal is a new transaction. The original entries stay as they were. A reversal cannot be reversed, and a transaction cannot be reversed twice.
- **Overdraft.** The check runs under the account lock. Two concurrent withdrawals of the whole limit produce one success and one `OVERDRAFT_LIMIT`.
- **Concurrency.** Locks are taken in account-id order. Concurrent credits of `1.00` land as `16.00`, not a lost update.
- **Idempotency.** Two callers with the same key get one transaction and the same body. A different body for that key conflicts and does not post again.
- **Payment redelivery.** `paymentId` P with event E1 and then event E2 records one settlement. A later different amount for P does not move the balance.
- **Announcements.** A recorded transaction shows up on `ledger.transactions.recorded`. Clearing `published_at` (the publisher died before the mark) causes the same transaction to be sent again.

## Trade-offs

- Client-supplied account ids, matching `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, so a caller can use `merchant-42` without a second lookup.
- The public prefix is `/v1`, not `/api/v1`. The service is only an API, and the version lives in the path.
- Persistence is JDBC and Flyway, not JPA. The locking SQL needs to stay visible.
- Idempotency fingerprints the raw amount text. `10` and `10.00` are different HTTP requests. Payment deduplication compares minor units, so those two amounts are the same payment.
- Refusals are stored under the idempotency key. A retry of a bad request stays a refusal instead of being re-executed.
- Sequence numbers come from `AUTO_INCREMENT`. They increase with commit order and can skip when a transaction rolls back.
- The sample publisher is on unless `ledger.samples.enabled=false`.

## Tests

The tests are in `src/test/java/com/ledger/guarantee/`. `LedgerGuaranteeTest` demonstrates the guarantees above, against real MySQL and RabbitMQ. `MoneyTest` covers scale, zero, and the `0.10 + 0.20` case without a database.

# Improvements

Before production I would:

- Add authentication and drop the sample payment endpoint, or gate it so it cannot be reached in production.
- Make the idempotency fingerprint compare minor units, so `10` and `10.00` are the same HTTP request.
- Retain idempotency rows for a defined time instead of forever.
- Add a primary consumer for `ledger.transactions.recorded` with its own deduplication, since delivery is at-least-once.
- Alert on `payment_intake_failures` and the dead-letter queue instead of only writing the row.
