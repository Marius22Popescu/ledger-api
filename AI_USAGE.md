# AI usage

Cursor was used to generate this service, the browser console, the tests, and these documents.

What was generated: the ledger domain, JDBC persistence, HTTP API, RabbitMQ consumer, transactional outbox, Docker assets, and `LedgerGuaranteeTest`.

What was reviewed against the challenge: money is integer minor units; every post is balanced before it is stored; the account lock, the entries, the balance update, and the outbox row commit in one database transaction; idempotency keys commit in that same transaction; payments are deduplicated by payment id; invalid payments are acknowledged and dead-lettered; reversals append a new transaction.

How it was checked: `MoneyTest` and `Iso4217Test` cover decimal scale without a broker. `LedgerGuaranteeTest` is the check that matters. It runs the application on MySQL 8.4 and RabbitMQ 4 and asserts overdraft races, opposite transfers, lost-update resistance, idempotent retries, payment redelivery with a new event id, dead-lettering, rollback of a refused post, reversals, and announcement republish. `./run.sh` starts the stack and returns that test process's exit code.

The instruction file used for the later pass is `.cursor/LEDGER_API_INSTRUCTIONS.md`.
