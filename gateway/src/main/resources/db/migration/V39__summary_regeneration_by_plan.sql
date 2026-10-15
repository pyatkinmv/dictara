-- "Regenerate summary" is limited by the user's plan, per transcription: `plans.constraints.regenerations_per_transcription`
-- (a number; no key means unlimited, like the other constraints). V38 allowed it once for everybody and kept that as
-- the state 'done'; now the times it was used are counted, and `summary_regeneration` only says 'running' (or NULL).
-- Additive only: the 'done' value stays allowed by the check, it is just not written any more.
ALTER TABLE submissions ADD COLUMN summary_regenerations INT NOT NULL DEFAULT 0;

UPDATE submissions SET summary_regenerations = 1, summary_regeneration = NULL WHERE summary_regeneration = 'done';

UPDATE plans SET constraints = constraints || '{"regenerations_per_transcription": 1}'::jsonb WHERE name IN ('anonymous', 'free');
