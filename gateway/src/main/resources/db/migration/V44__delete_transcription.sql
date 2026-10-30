-- Deleting a transcription removes the submission and, through the existing ON DELETE CASCADEs, its transcript, phrases,
-- summary, tags, speakers and suggestions. Two things were not ready for that:
--
-- 1. A delivery to Telegram pointed at the submission without a cascade, so a transcription that came from the bot
--    could not be deleted.
ALTER TABLE telegram_deliveries DROP CONSTRAINT IF EXISTS telegram_deliveries_job_id_fkey;
ALTER TABLE telegram_deliveries
    ADD CONSTRAINT telegram_deliveries_job_id_fkey FOREIGN KEY (job_id) REFERENCES submissions (id) ON DELETE CASCADE;

-- 2. The plan's limits count submissions, and deleting one must not give the allowance back (otherwise "upload, delete,
--    upload" is an unlimited plan). What is left of a deleted submission is only this: whose it was and when it was
--    made, no title, no text, no file. Failed submissions are not counted by the limits, so they leave nothing.
CREATE TABLE deleted_submissions (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX deleted_submissions_user_created_idx ON deleted_submissions (user_id, created_at);
