-- Development only. Applied through the `local` profile's Flyway location (see application-local.yaml);
-- production never sees this file. Holds the audio for LocalDbAudioStorage.
CREATE TABLE IF NOT EXISTS local_audio_objects (
    uri        TEXT PRIMARY KEY,
    data       BYTEA       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
