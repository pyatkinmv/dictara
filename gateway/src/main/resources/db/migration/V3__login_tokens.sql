CREATE TABLE login_tokens (
    token      UUID PRIMARY KEY,
    user_id    UUID REFERENCES users(id),
    confirmed  BOOLEAN NOT NULL DEFAULT false,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
