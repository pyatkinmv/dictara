-- User job list: called on every app open
CREATE INDEX idx_submissions_user_created ON submissions(user_id, created_at DESC);

-- Dedup JOIN: submissions → audio_meta lookup on every upload
CREATE INDEX idx_submissions_audio_id ON submissions(audio_id);

