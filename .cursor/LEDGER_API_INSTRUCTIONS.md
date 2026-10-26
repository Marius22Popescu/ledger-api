# LEDGER-API — MASTER INSTRUCTIONS FOR CURSOR

## 0. ROLE AND OBJECTIVE

You are the senior backend engineer responsible for designing, implementing and testing the complete **Ledger-API** project described in the attached specification.

The specification is authoritative. Do not silently change, omit or weaken any requirement.

The goal is to produce a production-quality backend implementation suitable for a backend coding challenge, with particular emphasis on:

- double-entry accounting correctness
- exact monetary calculations
- atomic transactions
- immutable ledger records
- concurrency correctness
- distributed idempotency
- message redelivery handling
- reliable transaction announcements
- clear REST API design
- integration tests against real MySQL and RabbitMQ
- Docker-based reproducibility
- excellent README/documentation

The official specification requires Java 17, Spring Boot 4, MySQL, REST and a message broker, with everything runnable through Docker.

The final implementation must satisfy the evaluation criteria around ledger integrity, idempotency, concurrency, retries, redelivery, event-driven design, API design, domain clarity and tests.

---

# 1. ABSOLUTE RULES

Before writing code:

1. Read the complete specification.
2. Inspect the existing repository.
3. Do not start implementing immediately.
4. First produce a detailed implementation plan.
5. Identify ambiguities and make explicit engineering decisions.
6. Wait for the planning phase to be internally complete before implementation.
7. Do not invent requirements that contradict the specification.
8. Do not weaken requirements merely because they make implementation harder.
9. Prefer correctness and transactional integrity over premature optimization.
10. Do not use floating-point types for money.
11. Do not use in-memory state for correctness-critical idempotency or concurrency.
12. Do not rely on a single application instance holding locks in memory.
13. All correctness guarantees must survive multiple application instances.
14. All important guarantees must be covered by automated tests.
15. Use real MySQL and real RabbitMQ in integration tests.
16. Everything must be reproducible using Docker.
17. Document all reasonable engineering decisions in README.md.
18. Commit the AI instructions used to generate the project, as required by the challenge.

The ledger must remain correct even when clients retry, messages are redelivered and requests race each other.

---

# 2. PHASE 1 — PLANNING

## 2.1 Repository inspection

First inspect:

- complete repository structure
- existing source code
- build configuration
- Docker configuration
- tests
- README
- configuration files
- Git status/history if available

Do not delete existing useful work without justification.

If the repository is empty, design the project from scratch.

---

## 2.2 Produce an architecture plan

Before implementation, define:

### Application architecture

Use a clean layered architecture, preferably:

- API / REST layer
- application/service layer
- domain layer
- persistence layer
- messaging layer
- infrastructure/configuration
- tests

Avoid unnecessary over-engineering.

Explain why the chosen architecture is appropriate for a ledger.

---

## 2.3 Technology decisions

Use:

- Java 17
- Spring Boot 4
- Spring Web
- Spring Data JPA / Hibernate where appropriate
- MySQL
- RabbitMQ
- Flyway or an equivalent explicit database migration mechanism
- JUnit 5
- Spring Boot Test
- Testcontainers where appropriate
- Docker / Docker Compose

Use exact monetary representation.

Recommended approach:

- store monetary amounts as integer minor units where possible
- represent EUR/USD in cents
- represent JPY in whole yen
- never use `double` or `float`
- use `BigDecimal` only where it provides a concrete benefit, but ensure persistence and arithmetic remain exact

Document the chosen representation.

---

# 3. DOMAIN MODEL

Design the domain around:

## Account

Fields should include at least:

- account ID
- currency
- overdraft limit
- current balance
- timestamps if useful
- version/concurrency information if required

The specification defines currency, overdraft limit and balance as core account properties.

Supported currencies are:

- EUR — 2 decimal places
- USD — 2 decimal places
- JPY — 0 decimal places

Only these three currencies are supported.

Reject unsupported currencies even if they are valid ISO 4217 currencies.

---

## Transaction

A transaction contains:

- transaction ID
- description
- entries
- recorded timestamp
- reversal reference when applicable

Transactions are immutable.

Never implement update/delete operations for ledger transactions.

The specification explicitly requires that recorded transactions and their entries are never updated or deleted.

---

## Entry

Each entry contains:

- account ID
- direction: DEBIT or CREDIT
- positive amount

The amount must:

- be greater than zero
- respect the account currency's decimal precision

The entry belongs to a transaction.

---

# 4. DOUBLE-ENTRY ACCOUNTING RULES

Implement the ledger rules centrally in the domain/application layer.

A transaction is valid only when:

1. It contains at least two entries.
2. Every amount is positive.
3. Every amount respects the currency precision.
4. Every referenced account exists.
5. All accounts belong to the same currency.
6. Total debits equal total credits.
7. No account exceeds its overdraft limit.
8. The complete transaction is persisted atomically.

These rules are explicit requirements.

Never partially persist a transaction.

If validation fails:

- no transaction is recorded
- no entry is recorded
- no balance is changed
- the caller receives a useful error
- the response identifies the relevant validation problem where possible

---

# 5. BALANCE MODEL

A credit increases an account balance.

A debit decreases an account balance.

Example:

Debit 100 EUR:

balance = balance - 100

Credit 100 EUR:

balance = balance + 100

The balance must never be derived using floating-point arithmetic.

---

# 6. OVERDRAFT AND CONCURRENCY

This is one of the most important parts of the implementation.

The application will run multiple instances.

Transactions involving the same account must remain correct when executed concurrently.

The implementation must guarantee:

- no lost updates
- no overdraft violation
- no deadlocks
- correct results for A → B and B → A occurring concurrently

The challenge explicitly requires these guarantees.

Do NOT solve this with:

- Java synchronized
- local locks
- static maps
- JVM-level mutexes
- application-local caches

Those solutions do not work across multiple application instances.

Use database-level concurrency control.

Evaluate and choose an appropriate strategy such as:

- pessimistic row locking
- optimistic locking with retry
- atomic SQL updates
- deterministic lock ordering

The preferred solution should be simple and robust.

If multiple account rows must be locked, lock them in deterministic account-ID order to prevent deadlocks.

For example:

A → B

and

B → A

must acquire locks in the same sorted order.

Explain this decision in README.md.

---

# 7. DATABASE TRANSACTION BOUNDARIES

A transaction recording operation must execute inside a single database transaction.

The following must happen atomically:

1. validate account existence
2. lock relevant accounts
3. validate currencies
4. validate amounts
5. validate debit/credit equality
6. validate overdraft
7. create transaction
8. create entries
9. update balances
10. persist idempotency information

If anything fails, everything rolls back.

Do not allow a transaction where the database ends up containing only part of the entries.

---

# 8. IDEMPOTENCY

Idempotency is a core requirement.

The same client request may be sent multiple times because the client did not receive the response.

The request must take effect at most once.

A retry must return the same logical result as the original request.

This must work:

- on another application instance
- after the first attempt partially executed
- after the first attempt committed but response delivery failed
- under concurrent duplicate requests

The specification explicitly requires this behavior for account creation, transaction recording and reversal.

Implement idempotency in persistent storage.

Recommended design:

- require an `Idempotency-Key` HTTP header for mutation operations
- persist the key
- associate it with the operation type and request fingerprint
- persist the resulting resource/result
- enforce a unique database constraint

If the same key is reused with a different request payload, reject it rather than treating it as the same request.

Document the exact behavior.

Important:

Do not rely only on:

```text
check key
if not exists:
    process
```

because two instances can execute the check concurrently.

Use a database constraint/transactional approach.

---

# 9. ACCOUNT CREATION IDEMPOTENCY

Opening an account must be idempotent.

Two concurrent requests with the same idempotency key must result in one logical account creation.

Two requests with different idempotency keys may represent different requests.

Do not accidentally use the account ID alone as the idempotency mechanism unless that is explicitly part of the API design.

---

# 10. TRANSACTION IDEMPOTENCY

Recording a transaction must be idempotent.

The idempotency record and the transaction must be persisted consistently.

The implementation must survive:

```text
request
→ transaction processing
→ database commit
→ application crash
→ client retry
```

The retry must not create a second transaction.

---

# 11. REVERSAL IDEMPOTENCY

Reversal must:

- create a new transaction
- flip every original entry
- link the new transaction to the original
- leave the original transaction unchanged
- allow an original transaction to be reversed only once
- never allow a reversal itself to be reversed

These are explicit requirements.

Use a database constraint where possible to guarantee only one reversal exists for a transaction.

Do not depend solely on application-level checks.

A reversal is itself subject to normal transaction rules, including overdraft limits.

---

# 12. PAYMENT-SETTLED MESSAGES

Use RabbitMQ.

The incoming event contains:

- event ID
- payment ID
- merchant account ID
- amount
- currency

The payment settlement operation must create:

DEBIT settlement account

CREDIT merchant account

The settlement accounts always exist.

This behavior is explicitly specified.

---

# 13. PAYMENT REDelivery / IDEMPOTENCY

This is separate from HTTP idempotency.

The same payment can arrive:

- multiple times
- with different event IDs

The same payment must be recorded exactly once.

Therefore do NOT deduplicate only by event ID.

Use payment ID as the business idempotency key.

Persist the processed payment ID using a unique database constraint.

The same payment arriving under another event ID must not create another ledger transaction.

Test this explicitly.

---

# 14. INVALID PAYMENT EVENTS

Malformed or impossible payment messages must:

- not create a transaction
- not block subsequent messages
- not disappear without trace

The specification explicitly requires this.

Design an appropriate failure strategy.

Recommended:

- acknowledge malformed/non-retryable messages after recording failure
- store an audit/failure record and/or publish to a dead-letter queue
- use RabbitMQ dead-lettering for messages that cannot be processed

Document:

- retry policy
- acknowledgement policy
- dead-letter policy
- what happens after application crash
- whether ordering is guaranteed

---

# 15. TRANSACTION ANNOUNCEMENTS

Every successfully recorded transaction must be announced.

This includes:

- normal REST transactions
- reversals
- payment-settled transactions

The specification deliberately leaves the mechanism open.

Use RabbitMQ for announcements.

Do not publish an event before the database transaction is safely persisted.

Avoid the classic problem:

```text
DB commit succeeds
RabbitMQ publish fails
```

Use an Outbox Pattern.

Recommended architecture:

Database transaction:

1. persist ledger transaction
2. persist ledger entries
3. update balances
4. persist outbox event

Then a separate publisher publishes the outbox event to RabbitMQ.

Only mark the outbox event as published after successful broker publication.

The publisher must be idempotent.

If an event is published twice, consumers should be expected to handle duplicates.

Document this guarantee.

---

# 16. OUTBOX DESIGN

Create an outbox table containing at least:

- event ID
- event type
- aggregate/resource ID
- payload
- created timestamp
- published timestamp/status
- retry count
- last error if useful

Use a background publisher.

Avoid holding database transactions open while waiting for RabbitMQ.

The ledger transaction and outbox event must be committed atomically.

---

# 17. EVENT DELIVERY GUARANTEES

Clearly document what consumers can rely on.

Recommended guarantee:

- events are durably stored before publication
- events are eventually published
- consumers may receive duplicates
- consumers must use event IDs/idempotency
- global ordering is NOT guaranteed
- per-aggregate ordering should be considered carefully
- a crash can cause publication retries

Do not claim exactly-once message delivery unless the implementation can actually prove it.

The README must explicitly explain:

- can an event be missed?
- can an event appear twice?
- is ordering guaranteed?
- what happens if the application crashes immediately after DB commit?

These questions are explicitly part of the challenge.

---

# 18. ACCOUNTING INTEGRITY

At every committed state:

For every currency:

```text
sum(account balances) = 0
```

This must hold for:

- REST transactions
- reversals
- payment settlements
- concurrent transactions

Never allow cross-currency transactions.

Examples that must fail:

EUR → JPY

USD → EUR

JPY amount with decimals

Unsupported currencies

Unknown accounts

---

# 19. API DESIGN

Design a clean REST API.

At minimum provide endpoints for:

### Accounts

```text
POST /api/v1/accounts
GET  /api/v1/accounts/{accountId}
```

### Transactions

```text
POST /api/v1/transactions
GET  /api/v1/transactions/{transactionId}
POST /api/v1/transactions/{transactionId}/reversal
```

The exact API contract is your responsibility.

Define:

- request DTOs
- response DTOs
- status codes
- error model
- validation errors
- idempotency behavior
- versioning

Use `/api/v1`.

Do not expose JPA entities directly.

---

# 20. ERROR MODEL

Create a consistent error response.

It should contain enough information for a caller to understand:

- what went wrong
- which field caused the error
- an application error code
- human-readable message
- correlation/request ID if available

Use appropriate HTTP status codes.

Examples:

- malformed request → 400
- invalid currency → 422 or 400
- unknown account → 404 or 422 depending on API semantics
- overdraft violation → 409 or 422
- idempotency conflict → 409
- transaction already reversed → 409

Choose deliberately and document the choices.

---

# 21. VALIDATION CASES

Implement tests and validation for at least:

### Valid

- EUR transaction
- USD transaction
- JPY transaction
- multiple entries
- transaction with multiple debit/credit entries
- valid reversal
- valid payment settlement

### Invalid

- one entry
- zero amount
- negative amount
- too many decimal places
- debit total != credit total
- unknown account
- mixed currencies
- unsupported currency
- overdraft exceeded
- second reversal
- reversal of reversal
- duplicate payment
- same idempotency key with different payload

The specification gives explicit examples of these rejection cases.

---

# 22. NICE-TO-HAVE FEATURES

Implement these if they can be done cleanly without compromising the core requirements:

1. API-first OpenAPI contract.
2. Paginated account transaction history, newest first.
3. Balance after each account entry.
4. Historical balance at a specified instant.
5. Correlation/request/payment tracing through logs and events.

These are explicitly listed as nice-to-have capabilities.

Do not sacrifice correctness for these features.

---

# 23. OBSERVABILITY

Implement structured logging.

Every request should have a correlation/request ID.

Where relevant, logs should include:

- request ID
- idempotency key
- transaction ID
- payment ID
- event ID
- account IDs
- operation
- processing result

Do not log sensitive data unnecessarily.

The challenge specifically values the ability to follow a request or payment through logs and announcements.

---

# 24. DATABASE DESIGN

Design normalized tables.

At minimum expect entities/tables similar to:

- accounts
- ledger_transactions
- ledger_entries
- idempotency_records
- payment_processing_records
- outbox_events

Potentially:

- account balance/version information
- dead-letter/failure audit
- audit records

Use explicit foreign keys and unique constraints.

Important constraints should be enforced by the database whenever possible.

Examples:

- unique account identifiers
- unique idempotency keys within their scope
- unique payment IDs
- one reversal per original transaction
- foreign keys for entries
- immutable transaction records

---

# 25. MONEY REPRESENTATION

Do not use:

```java
double
float
```

for monetary values.

Define a clear Money/value-object strategy.

The implementation must guarantee:

```text
0.10 + 0.20 = 0.30
```

exactly.

The challenge explicitly requires exact monetary arithmetic.

---

# 26. DATABASE MIGRATIONS

Use Flyway.

The database schema must be reproducible from migrations.

Do not require manual database setup.

Docker startup must create the database schema automatically.

---

# 27. DOCKER

Everything must run in Docker.

Provide:

```text
docker compose up
```

or a single project script that:

1. builds the application
2. starts MySQL
3. starts RabbitMQ
4. starts the application
5. waits for dependencies
6. executes tests
7. returns the correct exit code

The challenge explicitly states that only Docker, Git and a JDK should be required.

Do not require a locally installed MySQL or RabbitMQ.

---

# 28. TESTING STRATEGY

Testing is a first-class deliverable.

Do not stop at unit tests.

Use multiple levels:

## Unit tests

Test:

- money arithmetic
- currency precision
- transaction balancing
- overdraft validation
- reversal logic
- domain validation

## Repository/integration tests

Test against real MySQL.

Verify:

- transactions
- locks
- constraints
- rollback
- idempotency

## Messaging integration tests

Test against real RabbitMQ.

Verify:

- payment consumption
- acknowledgement
- redelivery
- duplicate payment handling
- dead-letter behavior
- outgoing transaction announcements

## Full integration tests

Run the complete application against:

- real MySQL
- real RabbitMQ

---

# 29. REQUIRED CONCURRENCY TESTS

These are mandatory.

Create tests where multiple threads/processes simultaneously attempt:

### Test 1

Two transactions debit the same account while only one can fit within the overdraft.

Expected:

- exactly one succeeds
- exactly one fails
- balance is correct

### Test 2

A → B and B → A execute concurrently.

Expected:

- no deadlock
- both transactions complete when valid
- balances are correct

### Test 3

Multiple concurrent credits/debits against the same account.

Expected:

- no lost update
- final balance equals the mathematically expected result

### Test 4

Two concurrent requests with the same idempotency key.

Expected:

- exactly one transaction
- both callers receive the same logical result

---

# 30. REQUIRED FAILURE/ROLLBACK TEST

Create a transaction that fails during validation or persistence.

Verify:

- no transaction exists
- no entries exist
- no balance changed
- no outbox event was created

The challenge explicitly requires tests proving that a refused transaction leaves nothing behind.

---

# 31. REQUIRED PAYMENT REDelivery TEST

Send:

```text
paymentId = P123
eventId = E1
```

Then send:

```text
paymentId = P123
eventId = E2
```

Expected:

- one ledger transaction
- one balance change
- duplicate payment is ignored safely
- both messages do not result in two settlements

This test is mandatory because the specification explicitly says the same payment may be resent with a new event ID.

---

# 32. REQUIRED CRASH/RETRY THINKING

Design tests or testable behavior around:

```text
DB transaction commits
application crashes
client retries
```

The implementation must not create a second transaction.

Also test:

```text
DB transaction commits
outbox event exists
publisher crashes
publisher restarts
```

The event must eventually be publishable.

---

# 33. API CONTRACT

Create an OpenAPI specification if practical.

The API contract should be created before implementation or at least kept synchronized with implementation.

The challenge explicitly lists API-first as a nice-to-have.

Prefer:

```text
src/main/resources/openapi.yaml
```

if this fits the project structure.

---

# 34. README

Create an excellent README.md.

It must contain:

## Overview

What Ledger-API does.

## Architecture

Explain:

- application structure
- database
- RabbitMQ
- outbox
- concurrency strategy
- idempotency strategy

## API

Document endpoints and examples.

## Running

Explain exactly how to start the project.

## Docker

Explain Docker Compose.

## Database

Explain schema and migrations.

## Messaging

Explain:

- payment-settled input
- transaction announcement
- retry behavior
- duplicate handling
- dead-letter behavior

## Consistency guarantees

Explicitly explain:

- atomicity
- immutability
- overdraft safety
- concurrency
- idempotency
- payment redelivery
- event delivery

## Trade-offs

Explain important decisions.

## Improvements

The README MUST end with an:

# Improvements

section.

Explain what would be changed before production and what was deliberately skipped.

This is an explicit deliverable requirement.

---

# 35. AI_USAGE.md

Create:

```text
AI_USAGE.md
```

Explain:

- that AI/Cursor was used
- which parts were generated
- which parts were reviewed
- how the generated code was verified
- which tests were written
- how correctness was checked

The challenge explicitly asks for a few words about AI usage and requires the instruction files to be committed.

Store this instruction set in the repository, for example:

```text
.cursor/LEDGER_API_INSTRUCTIONS.md
```

or an equivalent clearly named file.

---

# 36. CODE QUALITY RULES

Follow these principles:

- meaningful names
- small focused classes
- immutable domain objects where practical
- explicit business rules
- no business logic in controllers
- no business logic hidden in repositories
- no duplicated validation
- no magic numbers
- clear exception hierarchy
- clear transaction boundaries
- no unnecessary abstractions
- no speculative microservices
- no premature optimization

Prefer understandable code over clever code.

---

# 37. SECURITY / API HARDENING

Even though authentication is not required by the challenge:

- validate input
- limit request sizes where appropriate
- never trust client-provided balances
- never allow clients to modify transaction history
- never expose database internals
- do not expose stack traces in API responses

Authentication/authorization may be documented as a production improvement rather than implemented if not required.

---

# 38. PHASE 2 — EXECUTION

After completing the planning/design phase:

Implement the project incrementally.

Recommended order:

### Step 1

Project/build configuration.

### Step 2

Database migrations.

### Step 3

Domain model.

### Step 4

Money/currency logic.

### Step 5

Account creation/read.

### Step 6

Transaction recording.

### Step 7

Database concurrency strategy.

### Step 8

HTTP idempotency.

### Step 9

Reversal.

### Step 10

RabbitMQ infrastructure.

### Step 11

Payment-settled consumer.

### Step 12

Payment idempotency.

### Step 13

Outbox.

### Step 14

Transaction announcement.

### Step 15

Error handling.

### Step 16

Observability/correlation IDs.

### Step 17

OpenAPI.

### Step 18

Docker Compose.

### Step 19

Integration tests.

### Step 20

README and AI_USAGE.md.

After each major step:

- compile
- run relevant tests
- inspect failures
- fix root causes
- avoid accumulating broken code

Do not move forward while fundamental compilation or architectural problems remain unresolved.

---

# 39. PHASE 3 — TESTING

After implementation is complete, perform a dedicated testing phase.

Do not simply run the unit test suite and declare success.

Run:

```text
./mvnw test
```

and the complete Docker-based test flow.

Verify:

- application starts from a clean environment
- MySQL starts
- RabbitMQ starts
- migrations run
- tests execute
- all tests pass

Then specifically inspect the concurrency and messaging tests.

---

# 40. TEST MATRIX

Create a final test matrix covering:

| Area | Required |
|---|---|
| Account creation | YES |
| Account retrieval | YES |
| Currency validation | YES |
| Money precision | YES |
| Balanced transaction | YES |
| Unbalanced transaction | YES |
| Unknown account | YES |
| Mixed currencies | YES |
| Overdraft | YES |
| Atomic rollback | YES |
| Transaction immutability | YES |
| Reversal | YES |
| Double reversal | YES |
| Reversal of reversal | YES |
| HTTP idempotency | YES |
| Idempotency conflict | YES |
| Concurrent transaction | YES |
| A → B / B → A concurrency | YES |
| Payment settlement | YES |
| Payment duplicate | YES |
| Payment duplicate with new event ID | YES |
| Invalid payment message | YES |
| RabbitMQ redelivery | YES |
| Outbox | YES |
| Transaction announcement | YES |
| Docker startup | YES |

---

# 41. FINAL REVIEW

Before declaring the project finished, perform a code-review pass as if you were the evaluator.

Ask:

### Ledger correctness

Can any operation create an unbalanced ledger?

Can balances become incorrect?

Can an overdraft race succeed incorrectly?

Can an entry exist without its transaction?

Can a transaction be partially persisted?

### Idempotency

Can two instances process the same request simultaneously?

Can the same idempotency key represent two different requests?

Can the same payment be processed twice?

Can a payment with a different event ID bypass deduplication?

### Messaging

Can a transaction be committed without an announcement eventually being publishable?

Can an announcement be lost?

Can consumers receive duplicates?

Is that documented?

### Database

Are important uniqueness guarantees enforced at DB level?

Are transaction boundaries correct?

Can concurrent updates lose data?

### Reversal

Can a transaction be reversed twice?

Can a reversal be reversed?

Does the reversal respect overdraft?

### Testing

Are the guarantees actually proven by tests rather than merely asserted?

---

# 42. DO NOT DECLARE SUCCESS UNTIL

The project is not complete until:

1. It compiles.
2. Unit tests pass.
3. Integration tests pass.
4. MySQL integration tests pass.
5. RabbitMQ integration tests pass.
6. Concurrency tests pass.
7. Idempotency tests pass.
8. Payment redelivery tests pass.
9. Outbox tests pass.
10. Docker startup works from a clean environment.
11. README is complete.
12. AI_USAGE.md is complete.
13. The Cursor instruction file is committed.
14. The README contains the required Improvements section.
15. No requirement from the original specification has been silently omitted.

---

# 43. IMPORTANT CURSOR BEHAVIOR

Do NOT implement the entire project blindly in one pass.

Work in the following mode:

## PLAN

First analyze and design.

Create/update a plan containing:

- architecture
- domain model
- database schema
- API
- concurrency strategy
- idempotency strategy
- messaging strategy
- outbox strategy
- testing strategy
- Docker strategy
- documentation strategy

Identify ambiguities and explicitly choose reasonable solutions.

## EXECUTE

Then implement incrementally according to the plan.

After each major component:

- compile
- test
- review
- continue

## TEST

At the end perform a dedicated adversarial test pass.

Try to break:

- balances
- overdraft
- idempotency
- concurrency
- reversals
- payment redelivery
- messaging
- atomicity

If a test exposes a correctness problem, fix the architecture/code rather than weakening the test.

## FINAL REPORT

When finished, provide:

1. Architecture summary.
2. Important design decisions.
3. Files created/modified.
4. Database schema summary.
5. API summary.
6. RabbitMQ topology.
7. Idempotency strategy.
8. Concurrency strategy.
9. Outbox strategy.
10. Test results.
11. Docker execution instructions.
12. Remaining limitations.
13. Production improvements.

Never claim a guarantee that has not been demonstrated by implementation or tests.

---

# 44. SOURCE OF TRUTH

The attached Ledger API specification is the primary source of truth.

Where the specification explicitly defines behavior, follow it.

Where it deliberately leaves a design choice open, make a reasonable engineering decision and document it in README.md.

Do not invent additional business requirements.

The specification explicitly says that unspecified decisions are yours to make, provided they are documented.

END OF INSTRUCTIONS.
