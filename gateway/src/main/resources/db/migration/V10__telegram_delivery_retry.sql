ALTER TABLE telegram_deliveries
    ADD COLUMN claimed_at     TIMESTAMPTZ,
    ADD COLUMN retry_after_ts TIMESTAMPTZ,
    ADD COLUMN attempt_count  INT NOT NULL DEFAULT 0;
