-- NULL = audio bytes are stored in audio_content (legacy/local-dev path).
-- gs://... = audio is held in a GCS bucket and referenced by URI (Cloud Run path,
-- needed because Cloud Run enforces a hard 32 MiB HTTP request body limit).
ALTER TABLE audio_meta ADD COLUMN storage_uri TEXT;
