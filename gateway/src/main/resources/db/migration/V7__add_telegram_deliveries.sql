CREATE TABLE telegram_deliveries (
    job_id       UUID PRIMARY KEY REFERENCES submissions(id),
    chat_id      BIGINT NOT NULL,
    delivered_at TIMESTAMPTZ
);
