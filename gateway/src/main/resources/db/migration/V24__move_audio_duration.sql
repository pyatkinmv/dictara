ALTER TABLE transcripts DROP COLUMN audio_duration_s;
ALTER TABLE audio_meta ADD COLUMN duration_s DOUBLE PRECISION;
