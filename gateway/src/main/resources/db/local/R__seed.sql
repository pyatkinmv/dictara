-- Development only: data for the `local` profile (see application-local.yaml).
--
-- A repeatable migration runs again whenever this file changes, so every statement must be idempotent:
-- fixed ids and ON CONFLICT DO NOTHING. New data for new screens is appended here.

-- The user every request belongs to when no token is sent (LocalDevAuthFilter).
-- 'unlimited' so that uploading files is not stopped by the free plan's monthly limit.
INSERT INTO users (id, display_name, plan)
VALUES ('00000000-0000-4000-8000-000000000001', 'Dev User', 'unlimited')
ON CONFLICT (id) DO NOTHING;
