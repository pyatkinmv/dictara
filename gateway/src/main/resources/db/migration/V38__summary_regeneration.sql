-- "Regenerate summary" on a transcription: the user may do it once. NULL = not used yet (the button is there),
-- 'running' = Gemini is being asked, 'done' = used. A failed run puts it back to NULL so it can be tried again.
-- Additive only.
ALTER TABLE submissions
    ADD COLUMN summary_regeneration   VARCHAR(16) CHECK (summary_regeneration IN ('running', 'done')),
    ADD COLUMN summary_regenerated_at TIMESTAMPTZ;
