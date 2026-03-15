ALTER TABLE login_tokens
    ADD COLUMN rejected         BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN pending_username VARCHAR(255);

CREATE TABLE login_notifications (
    id         BIGSERIAL PRIMARY KEY,
    token      UUID NOT NULL REFERENCES login_tokens(token),
    chat_id    TEXT NOT NULL,
    sent       BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
