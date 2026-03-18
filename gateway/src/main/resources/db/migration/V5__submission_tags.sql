CREATE TABLE submission_tags (
    submission_id UUID        NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    tag           VARCHAR(64) NOT NULL,
    PRIMARY KEY (submission_id, tag)
);
