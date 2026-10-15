-- When the one-off backlog run (POST /admin/reanalyze) asked Gemini again about this transcription. It is only a
-- marker, so that sending the same ids twice does not pay for the same transcription twice; it has nothing to do with
-- the user's own "Regenerate" (summary_regenerations). Additive only.
ALTER TABLE submissions ADD COLUMN analysis_backfilled_at TIMESTAMPTZ;
