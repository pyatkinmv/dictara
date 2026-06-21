-- User job list: called on every app open
CREATE INDEX idx_submissions_user_created ON submissions(user_id, created_at DESC);

-- Dedup JOIN: submissions → audio_meta lookup on every upload
CREATE INDEX idx_submissions_audio_id ON submissions(audio_id);

-- Telegram auth: findByTelegramUsername called on every bot message
CREATE INDEX idx_auth_identities_tg_username ON auth_identities ((metadata->>'username')) WHERE provider = 'telegram';

-- Delivery polling: findPendingDeliveries called every few seconds
CREATE INDEX idx_telegram_deliveries_pending ON telegram_deliveries (retry_after_ts) WHERE delivered_at IS NULL AND attempt_count < 10;
