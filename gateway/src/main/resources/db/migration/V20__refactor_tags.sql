CREATE TABLE tags (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(32) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);

INSERT INTO tags (user_id, name)
SELECT DISTINCT s.user_id, st.tag
FROM submission_tags st
JOIN submissions s ON s.id = st.submission_id;

ALTER TABLE submission_tags ADD COLUMN tag_id UUID REFERENCES tags(id) ON DELETE CASCADE;

UPDATE submission_tags st
SET tag_id = t.id
FROM submissions s, tags t
WHERE s.id = st.submission_id
  AND t.user_id = s.user_id
  AND t.name = st.tag;

ALTER TABLE submission_tags DROP COLUMN tag;
ALTER TABLE submission_tags ALTER COLUMN tag_id SET NOT NULL;
ALTER TABLE submission_tags ADD PRIMARY KEY (submission_id, tag_id);
