CREATE TABLE transcript_tags (
    transcript_id UUID NOT NULL REFERENCES transcripts(id) ON DELETE CASCADE,
    tag_id        UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (transcript_id, tag_id)
);

INSERT INTO transcript_tags (transcript_id, tag_id)
SELECT t.id, st.tag_id
FROM submission_tags st
JOIN transcripts t ON t.submission_id = st.submission_id;

DROP TABLE submission_tags;
