-- stage_attempts: queried by submission_id + stage on every orchestration cycle
CREATE INDEX idx_stage_attempts_submission_id ON stage_attempts(submission_id);

-- login_notifications: polled every 2s by tg-bot
CREATE INDEX idx_login_notifications_token ON login_notifications(token);

-- submission_tags: PK covers (submission_id, tag_id) but not tag_id alone
CREATE INDEX idx_submission_tags_tag_id ON submission_tags(tag_id);

-- submission_speakers: PK covers (submission_id, speaker_id) but not speaker_id alone
CREATE INDEX idx_submission_speakers_speaker_id ON submission_speakers(speaker_id);

-- auth_identities: lookups by user_id during auth flows
CREATE INDEX idx_auth_identities_user_id ON auth_identities(user_id);

-- audio_meta: lookups by user_id
CREATE INDEX idx_audio_meta_user_id ON audio_meta(user_id);
