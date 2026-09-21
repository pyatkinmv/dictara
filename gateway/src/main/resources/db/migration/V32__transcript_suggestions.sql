-- What the AI proposes for a finished transcription: a new title, tags of the user's library, and who
-- a diarizer voice is. Nothing here is applied to the transcript; a proposal takes effect only when the user
-- accepts it, and a rejected or accepted row stays (with its status) so it is never proposed again.
--
-- A speaker proposal is a NAME (plus the quote that shows it), not a library speaker: the speaker is created
-- in the library only when the proposal is accepted. When the name is one the library already has, speaker_id
-- points at that speaker (and goes away with it).
CREATE TABLE transcript_suggestions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transcript_id UUID NOT NULL REFERENCES transcripts(id) ON DELETE CASCADE,
    kind          VARCHAR(16) NOT NULL CHECK (kind IN ('title', 'tag', 'speaker')),
    status        VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'accepted', 'rejected')),
    title         VARCHAR(200),
    tag_id        UUID REFERENCES tags(id) ON DELETE CASCADE,
    voice         VARCHAR(64),
    speaker_name  VARCHAR(64),
    speaker_id    UUID REFERENCES speakers(id) ON DELETE CASCADE,
    evidence      TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    CONSTRAINT transcript_suggestions_shape CHECK (
        (kind = 'title'   AND title IS NOT NULL AND tag_id IS NULL AND voice IS NULL AND speaker_name IS NULL AND speaker_id IS NULL)
     OR (kind = 'tag'     AND tag_id IS NOT NULL AND title IS NULL AND voice IS NULL AND speaker_name IS NULL AND speaker_id IS NULL)
     OR (kind = 'speaker' AND voice IS NOT NULL AND speaker_name IS NOT NULL AND title IS NULL AND tag_id IS NULL)
    )
);

-- One proposal of each kind per subject: one title, one row per tag, one per voice.
CREATE UNIQUE INDEX uq_transcript_suggestions_title   ON transcript_suggestions (transcript_id) WHERE kind = 'title';
CREATE UNIQUE INDEX uq_transcript_suggestions_tag     ON transcript_suggestions (transcript_id, tag_id) WHERE kind = 'tag';
CREATE UNIQUE INDEX uq_transcript_suggestions_speaker ON transcript_suggestions (transcript_id, voice) WHERE kind = 'speaker';
CREATE INDEX idx_transcript_suggestions_tag_id     ON transcript_suggestions (tag_id) WHERE tag_id IS NOT NULL;
CREATE INDEX idx_transcript_suggestions_speaker_id ON transcript_suggestions (speaker_id) WHERE speaker_id IS NOT NULL;
