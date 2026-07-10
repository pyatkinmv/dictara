CREATE TABLE transcript_speakers (
    transcript_id UUID NOT NULL REFERENCES transcripts(id) ON DELETE CASCADE,
    speaker_id    UUID NOT NULL REFERENCES speakers(id) ON DELETE CASCADE,
    PRIMARY KEY (transcript_id, speaker_id)
);

INSERT INTO transcript_speakers (transcript_id, speaker_id)
SELECT t.id, ss.speaker_id
FROM submission_speakers ss
JOIN transcripts t ON t.submission_id = ss.submission_id;

DROP TABLE submission_speakers;
