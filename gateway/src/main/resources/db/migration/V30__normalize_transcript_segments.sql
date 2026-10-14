-- A transcript's phrases become rows instead of one JSON cell (transcripts.segments).
--
-- Why: a phrase had no identity (only "position in the array"), so nothing could point at it; counting
-- speakers, summing speaking time or searching text meant parsing JSON on every read; and there was no
-- place to say who speaks a phrase. See docs/local-backend-integration.md, "Итерация 5a".
--
-- What this migration does, in order, in one transaction (a failure anywhere undoes everything, the
-- schema stays at V29 and the gateway refuses to start; the previous version keeps working):
--   1. checks that every existing transcript is exactly what we expect and stops loudly if not;
--   2. creates transcript_segments (one row per phrase) and transcript_voices (which library speaker
--      a diarizer voice is, per transcript);
--   3. copies every phrase from the JSON;
--   4. verifies the copy: per transcript the same number of phrases and the same texts in the same order.
--
-- transcripts.segments is NOT touched: it stays as it is and keeps being written next to the new rows for
-- one release, so the previous version of the gateway still works on this schema (rollback).
-- TODO(drop-json-segments): after that release drop transcripts.segments and the code that writes it.

-- 1. Refuse dirty data. Not coerced, not skipped: a person should look at it.
DO $$
DECLARE
    not_an_array  bigint;
    bad_elements  bigint;
    example       uuid;
BEGIN
    SELECT count(*) INTO not_an_array
    FROM transcripts
    WHERE segments IS NOT NULL AND jsonb_typeof(segments) NOT IN ('array', 'null');
    IF not_an_array > 0 THEN
        SELECT id INTO example FROM transcripts
        WHERE segments IS NOT NULL AND jsonb_typeof(segments) NOT IN ('array', 'null') LIMIT 1;
        RAISE EXCEPTION 'V30: % transcript(s) have segments that are not a JSON array (e.g. transcript %)', not_an_array, example;
    END IF;

    -- every phrase must be an object with numeric start and end, a string text and a string or null speaker
    SELECT count(*) INTO bad_elements
    FROM transcripts t, jsonb_array_elements(CASE WHEN jsonb_typeof(t.segments) = 'array' THEN t.segments ELSE '[]'::jsonb END) e
    WHERE jsonb_typeof(e) <> 'object'
       OR jsonb_typeof(e -> 'start') IS DISTINCT FROM 'number'
       OR jsonb_typeof(e -> 'end') IS DISTINCT FROM 'number'
       OR jsonb_typeof(e -> 'text') IS DISTINCT FROM 'string'
       OR jsonb_typeof(e -> 'speaker') NOT IN ('string', 'null');  -- an absent speaker gives NULL here: fine
    IF bad_elements > 0 THEN
        SELECT t.id INTO example
        FROM transcripts t, jsonb_array_elements(CASE WHEN jsonb_typeof(t.segments) = 'array' THEN t.segments ELSE '[]'::jsonb END) e
        WHERE jsonb_typeof(e) <> 'object'
           OR jsonb_typeof(e -> 'start') IS DISTINCT FROM 'number'
           OR jsonb_typeof(e -> 'end') IS DISTINCT FROM 'number'
           OR jsonb_typeof(e -> 'text') IS DISTINCT FROM 'string'
           OR jsonb_typeof(e -> 'speaker') NOT IN ('string', 'null')
        LIMIT 1;
        RAISE EXCEPTION 'V30: % phrase(s) are not {start, end, text, speaker} objects of the expected types (e.g. in transcript %)', bad_elements, example;
    END IF;
END $$;

-- 2. The tables.
CREATE TABLE transcript_segments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transcript_id UUID NOT NULL REFERENCES transcripts(id) ON DELETE CASCADE,
    idx           INT NOT NULL,                     -- position in the transcript, from 0
    start_s       DOUBLE PRECISION NOT NULL,
    end_s         DOUBLE PRECISION NOT NULL,
    text          TEXT NOT NULL,
    voice         VARCHAR(64),                      -- the diarizer's label (SPEAKER_01); null without diarization
    speaker_id    UUID REFERENCES speakers(id) ON DELETE SET NULL,  -- set by hand for this phrase only
    UNIQUE (transcript_id, idx)
);
CREATE INDEX idx_transcript_segments_speaker_id ON transcript_segments(speaker_id) WHERE speaker_id IS NOT NULL;

-- Which library speaker a voice is in one transcript. Who speaks a phrase: its own speaker_id, else the
-- speaker of its voice here, else nobody ("Speaker N" on screen).
CREATE TABLE transcript_voices (
    transcript_id UUID NOT NULL REFERENCES transcripts(id) ON DELETE CASCADE,
    voice         VARCHAR(64) NOT NULL,
    speaker_id    UUID NOT NULL REFERENCES speakers(id) ON DELETE CASCADE,
    PRIMARY KEY (transcript_id, voice)
);
CREATE INDEX idx_transcript_voices_speaker_id ON transcript_voices(speaker_id);

-- 3. Copy the phrases.
INSERT INTO transcript_segments (transcript_id, idx, start_s, end_s, text, voice)
SELECT t.id,
       (e.ord - 1)::int,
       (e.val ->> 'start')::double precision,
       (e.val ->> 'end')::double precision,
       e.val ->> 'text',
       e.val ->> 'speaker'
FROM transcripts t,
     jsonb_array_elements(CASE WHEN jsonb_typeof(t.segments) = 'array' THEN t.segments ELSE '[]'::jsonb END)
         WITH ORDINALITY AS e(val, ord);

-- 4. Verify the copy, transcript by transcript: the same number of phrases, and the same texts in the same order.
CREATE TEMP TABLE v30_mismatch ON COMMIT DROP AS
SELECT t.id
FROM transcripts t
LEFT JOIN (SELECT transcript_id, count(*) AS n, md5(string_agg(text, E'\x1f' ORDER BY idx)) AS h
           FROM transcript_segments GROUP BY transcript_id) s ON s.transcript_id = t.id
WHERE coalesce(s.n, 0) <> CASE WHEN jsonb_typeof(t.segments) = 'array' THEN jsonb_array_length(t.segments) ELSE 0 END
   OR coalesce(s.h, '') <> coalesce((
          SELECT md5(string_agg(x.val ->> 'text', E'\x1f' ORDER BY x.ord))
          FROM jsonb_array_elements(CASE WHEN jsonb_typeof(t.segments) = 'array' THEN t.segments ELSE '[]'::jsonb END)
                   WITH ORDINALITY AS x(val, ord)), '');

DO $$
DECLARE
    mismatched bigint;
    example    uuid;
BEGIN
    SELECT count(*), min(id::text)::uuid INTO mismatched, example FROM v30_mismatch;
    IF mismatched > 0 THEN
        RAISE EXCEPTION 'V30: the copy of % transcript(s) does not match the JSON (e.g. transcript %)', mismatched, example;
    END IF;
END $$;
