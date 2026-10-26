ALTER TABLE outbox_messages
    ADD COLUMN claimed_at TIMESTAMP(6) NULL AFTER created_at;
