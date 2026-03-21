-- Enforces the invariant: at most one submission can be 'processing' at a time.
-- The orchestrator dispatches one job at a time; this makes it a hard DB-level guarantee.
-- Note: ensure no stale 'processing' rows exist before deploying (clean them up manually).
CREATE UNIQUE INDEX idx_one_processing_submission ON submissions (status) WHERE status = 'processing';
