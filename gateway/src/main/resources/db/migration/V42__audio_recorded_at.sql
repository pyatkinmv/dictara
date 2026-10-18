-- When the recording was made (not when it was uploaded: audio_meta.created_at). Filled from the container's own
-- creation_time ('metadata', exact, UTC), else from a date in the file name ('filename', a local wall-clock time
-- read in an assumed zone), or by hand ('manual'). NULL when nothing tells it; clients then fall back to the upload
-- time. Additive only.
ALTER TABLE audio_meta ADD COLUMN recorded_at TIMESTAMPTZ;
ALTER TABLE audio_meta ADD COLUMN recorded_at_source VARCHAR(16);
ALTER TABLE audio_meta ADD CONSTRAINT audio_meta_recorded_at_source_check
    CHECK (recorded_at_source IN ('metadata', 'filename', 'manual'));
