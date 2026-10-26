CREATE TABLE accounts (
    id VARCHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    overdraft_limit_minor BIGINT NULL,
    balance_minor BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_accounts_currency CHECK (currency IN ('EUR', 'USD', 'JPY'))
) ENGINE=InnoDB;

INSERT INTO accounts (id, currency, overdraft_limit_minor, balance_minor, created_at) VALUES
    ('settlement-eur', 'EUR', NULL, 0, UTC_TIMESTAMP(6)),
    ('settlement-usd', 'USD', NULL, 0, UTC_TIMESTAMP(6)),
    ('settlement-jpy', 'JPY', NULL, 0, UTC_TIMESTAMP(6));

CREATE TABLE transactions (
    id CHAR(36) NOT NULL,
    sequence_no BIGINT NOT NULL AUTO_INCREMENT,
    description VARCHAR(500) NOT NULL,
    currency CHAR(3) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    reverses_transaction_id CHAR(36) NULL,
    payment_id VARCHAR(128) NULL,
    request_id VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_transactions_sequence (sequence_no),
    UNIQUE KEY uq_transactions_reverses (reverses_transaction_id),
    UNIQUE KEY uq_transactions_payment (payment_id),
    CONSTRAINT fk_transactions_reverses FOREIGN KEY (reverses_transaction_id) REFERENCES transactions (id)
) ENGINE=InnoDB;

CREATE TABLE entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id CHAR(36) NOT NULL,
    ordinal_no INT NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    direction VARCHAR(6) NOT NULL,
    amount_minor BIGINT NOT NULL,
    balance_after_minor BIGINT NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_entries_tx_ordinal (transaction_id, ordinal_no),
    KEY idx_entries_account_time (account_id, recorded_at, id),
    CONSTRAINT fk_entries_tx FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_entries_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT chk_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_entries_amount CHECK (amount_minor > 0)
) ENGINE=InnoDB;

CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    http_status INT NOT NULL,
    response_body MEDIUMTEXT NOT NULL,
    location VARCHAR(255) NULL,
    completed TINYINT(1) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (idempotency_key)
) ENGINE=InnoDB;

CREATE TABLE payment_intake_failures (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(128) NULL,
    payment_id VARCHAR(128) NULL,
    reason_code VARCHAR(64) NOT NULL,
    detail VARCHAR(2000) NOT NULL,
    raw_body MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_failures_payment (payment_id)
) ENGINE=InnoDB;

CREATE TABLE outbox_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id CHAR(36) NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_outbox_transaction (transaction_id),
    KEY idx_outbox_unpublished (published_at, id)
) ENGINE=InnoDB;
