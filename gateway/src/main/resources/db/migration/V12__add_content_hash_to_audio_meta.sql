ALTER TABLE audio_meta ADD COLUMN content_hash VARCHAR(64);
CREATE INDEX idx_audio_meta_user_content_hash ON audio_meta(user_id, content_hash);
