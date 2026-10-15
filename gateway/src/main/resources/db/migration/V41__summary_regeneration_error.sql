-- Why the last "Regenerate summary" of a transcription failed, in words for the user (e.g. "out of credits"); NULL when it did not fail, or after the next attempt starts or succeeds. Additive only.
ALTER TABLE submissions ADD COLUMN summary_regeneration_error VARCHAR(300);
