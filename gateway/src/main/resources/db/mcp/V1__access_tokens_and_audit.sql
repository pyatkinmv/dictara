-- Everything the MCP feature owns lives in the `mcp` schema, migrated by its own Flyway (McpSchemaMigrator), with its
-- own history table `mcp.flyway_schema_history`. It only points at the main schema through users(id).

-- A personal access token for AI agents. Only the SHA-256 of the token is kept; the token itself is shown once, when
-- it is created. `access` is what the token may do: 'read', or 'write' (which includes read).
CREATE TABLE access_tokens (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES public.users (id) ON DELETE CASCADE,
    name         VARCHAR(80) NOT NULL,
    token_hash   BYTEA       NOT NULL UNIQUE,
    token_prefix VARCHAR(12) NOT NULL,
    access       VARCHAR(8)  NOT NULL CHECK (access IN ('read', 'write')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);

CREATE INDEX access_tokens_user_idx ON access_tokens (user_id) WHERE revoked_at IS NULL;

-- One row per tool call: which token, which tool, how it ended. Never the arguments or the text that came back.
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    token_id    UUID REFERENCES access_tokens (id) ON DELETE SET NULL,
    user_id     UUID,
    tool        VARCHAR(64) NOT NULL,
    outcome     VARCHAR(16) NOT NULL CHECK (outcome IN ('ok', 'denied', 'error')),
    duration_ms INTEGER     NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_token_at_idx ON audit_log (token_id, at DESC);
