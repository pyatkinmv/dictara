-- Development only: data for the `local` profile (see application-local.yaml).
--
-- A repeatable migration runs again whenever this file changes, so it is written to be re-run: every row
-- has a fixed id and is upserted (ON CONFLICT ... DO UPDATE), which means that
--   * what is added here later shows up in an existing database without a reset, and
--   * rows that exist here are put back to what this file says (a hand-made rename of a seeded
--     transcription is undone the next time the file changes).
-- Rows removed from this file are NOT removed from an existing database; use scripts/dev-reset.ps1.
--
-- Not seeded on purpose: `pending` and `processing` transcriptions. The orchestrator picks such rows up at
-- startup and works them off with the stub transcriber, so they would not stay as they are written here.
-- Upload a file to see those states.

-- ── The user every request belongs to when no token is sent (LocalDevAuthFilter) ─────────────────────────
-- 'unlimited' so that uploading files is not stopped by the free plan's monthly limit.
INSERT INTO users (id, display_name, plan)
VALUES ('00000000-0000-4000-8000-000000000001', 'Dev User', 'unlimited')
ON CONFLICT (id) DO UPDATE SET display_name = EXCLUDED.display_name, plan = EXCLUDED.plan;

-- ── Transcriptions from the design (designs/1.9/cards-teal.html) ─────────────────────────────────────────
-- One row per transcription: n is the number in the ids (audio ...0a<n>, submission ...0b<n>,
-- transcript ...0c<n>), speakers how many voices the diarizer found (0 = no diarization), lines how many
-- lines of the sample conversation below the transcript has.
CREATE TEMP TABLE seed_items ON COMMIT DROP AS
SELECT * FROM (VALUES
    (0, 'quarterly_review_2025-06-22.m4a', 'Quarterly Review Meeting',        'audio/mp4',  862.0, 13800000, '2025-06-22 14:41:00+00'::timestamptz, 'done',   3, 10),
    (2, 'interview_anna_k.mp3',            'Interview: Anna K.',              'audio/mpeg', 2820.0, 45100000, '2025-06-21 16:20:00+00'::timestamptz, 'done',   2,  8),
    (4, 'voice_memo_2025-06-20.m4a',       'Product Ideas — Voice Memo',      'audio/mp4',  720.0, 11500000, '2025-06-20 18:02:00+00'::timestamptz, 'done',   0,  4),
    (5, 'standup_2025-06-19.mp3',          'Daily Standup 19 Jun',            'audio/mpeg', 480.0,  7700000, '2025-06-19 10:00:00+00'::timestamptz, 'done',   3,  6),
    (6, 'product_sync_2025_06_23.mp4',    NULL,                               'video/mp4',  2040.0, 23488102, '2025-06-23 13:15:00+00'::timestamptz, 'done',   3, 10),
    (7, 'broken_recording.mp3',            NULL,                              'audio/mpeg', NULL,    1200000, '2025-06-18 09:00:00+00'::timestamptz, 'failed', 0,  0)
) AS t(n, file_name, title, content_type, duration_s, size_bytes, created_at, status, speakers, lines);

INSERT INTO audio_meta (id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash, duration_s, created_at)
SELECT ('00000000-0000-4000-8000-0000000a00' || lpad(n::text, 2, '0'))::uuid,
       '00000000-0000-4000-8000-000000000001',
       file_name, content_type, size_bytes,
       'db://seed/' || file_name,           -- no audio is stored for the seed; only the reference exists
       'seed-audio-' || n,
       duration_s, created_at
FROM seed_items
ON CONFLICT (id) DO UPDATE SET
    original_name = EXCLUDED.original_name, content_type = EXCLUDED.content_type, size_bytes = EXCLUDED.size_bytes,
    duration_s = EXCLUDED.duration_s, created_at = EXCLUDED.created_at;

INSERT INTO submissions (id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status, created_at, updated_at)
SELECT ('00000000-0000-4000-8000-0000000b00' || lpad(n::text, 2, '0'))::uuid,
       '00000000-0000-4000-8000-000000000001',
       ('00000000-0000-4000-8000-0000000a00' || lpad(n::text, 2, '0'))::uuid,
       'fast', 'auto', speakers > 0, 'off', 'web', status, created_at, created_at + interval '2 minutes'
FROM seed_items
ON CONFLICT (id) DO UPDATE SET
    diarize = EXCLUDED.diarize, status = EXCLUDED.status, created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at;

-- Transcripts (only for finished transcriptions) with segments cut from a sample conversation: speakers are
-- labelled SPEAKER_00, SPEAKER_01, ... like the diarizer does, and null when there is no diarization.
WITH conversation(n, start_s, end_s, speaker, body) AS (VALUES
    (1,    0,  30, 0, 'Good morning everyone. Let''s get started — we have a lot to cover today. The main focus is Q2 performance and planning the roadmap for the next quarter.'),
    (2,   34,  70, 1, 'Right. So Q2 was solid overall — revenue is up 18% quarter over quarter. The enterprise segment grew faster than expected, mostly driven by the new integrations we launched in May.'),
    (3,   72, 120, 2, 'On the product side, we shipped 12 features versus the 9 we planned, but we also accumulated some technical debt in the payments module that we need to address in Q3.'),
    (4,  125, 145, 0, 'That''s great to hear on the feature side. What''s the plan for Q3? Are we maintaining the same pace or scaling up the team?'),
    (5,  148, 185, 1, 'We''re planning to hire 4 more engineers by September, which will let us run two parallel development tracks. That should improve throughput significantly without sacrificing quality.'),
    (6,  190, 205, 2, 'And the technical debt? How much time are we allocating to paying that down before we start adding more features on top?'),
    (7,  210, 240, 1, 'We''re reserving 20% of engineering capacity in July specifically for the payments refactor. It''s not glamorous but it''s necessary — otherwise we risk reliability issues in the fall when volume spikes.'),
    (8,  245, 258, 0, 'Makes sense. Alright, let''s move on to marketing. What''s the Q3 strategy looking like?'),
    (9,  262, 300, 2, 'We''re doubling down on content marketing — SEO-driven articles, a revamped case study library, and a monthly webinar series starting in August. These should bring in higher-intent leads than the paid campaigns we''ve relied on.'),
    (10, 308, 338, 1, 'I like the webinar idea. Can we make sure sales is looped in from the start? They should be co-presenting, not just watching.')
)
INSERT INTO transcripts (id, submission_id, segments, detected_language, title, created_at)
SELECT ('00000000-0000-4000-8000-0000000c00' || lpad(i.n::text, 2, '0'))::uuid,
       ('00000000-0000-4000-8000-0000000b00' || lpad(i.n::text, 2, '0'))::uuid,
       (SELECT jsonb_agg(
                   jsonb_build_object(
                       'start', c.start_s,
                       'end', c.end_s,
                       'text', c.body,
                       'speaker', CASE WHEN i.speakers = 0 THEN NULL ELSE 'SPEAKER_0' || (c.speaker % i.speakers) END
                   ) ORDER BY c.n)
        FROM conversation c
        WHERE c.n <= i.lines),
       'en', i.title, i.created_at + interval '2 minutes'
FROM seed_items i
WHERE i.status = 'done'
ON CONFLICT (id) DO UPDATE SET
    segments = EXCLUDED.segments, detected_language = EXCLUDED.detected_language, title = EXCLUDED.title;

-- ── Tags with the descriptions of the design's Tags page, and which transcriptions carry them ────────────
-- created_at fixes the order the library lists them in (the design's order), which the default now() would not.
INSERT INTO tags (id, user_id, name, description, created_at)
VALUES
    ('00000000-0000-4000-8000-0000000d0001', '00000000-0000-4000-8000-000000000001', 'meeting',   'Regular team meetings, standups, and group discussions.', '2025-06-01 09:00:01+00'),
    ('00000000-0000-4000-8000-0000000d0002', '00000000-0000-4000-8000-000000000001', 'quarterly', 'End-of-quarter review sessions covering performance and roadmap.', '2025-06-01 09:00:02+00'),
    ('00000000-0000-4000-8000-0000000d0003', '00000000-0000-4000-8000-000000000001', 'interview', 'Candidate interviews for open positions.', '2025-06-01 09:00:03+00'),
    ('00000000-0000-4000-8000-0000000d0004', '00000000-0000-4000-8000-000000000001', 'hiring',    'Recruitment-related recordings including screens and debriefs.', '2025-06-01 09:00:04+00'),
    ('00000000-0000-4000-8000-0000000d0005', '00000000-0000-4000-8000-000000000001', 'ideas',     'Brainstorming sessions and exploratory discussions.', '2025-06-01 09:00:05+00'),
    ('00000000-0000-4000-8000-0000000d0006', '00000000-0000-4000-8000-000000000001', 'standup',   'Daily standup recordings. Short format, multiple speakers.', '2025-06-01 09:00:06+00')
ON CONFLICT (user_id, name) DO UPDATE SET description = EXCLUDED.description, created_at = EXCLUDED.created_at;

-- The tag is looked up by name: a tag of the same name made by hand keeps its own id.
INSERT INTO transcript_tags (transcript_id, tag_id)
SELECT ('00000000-0000-4000-8000-0000000c00' || lpad(link.n::text, 2, '0'))::uuid, t.id
FROM (VALUES
    (0, 'meeting'), (0, 'quarterly'),
    (2, 'interview'), (2, 'hiring'),
    (4, 'ideas'),
    (5, 'meeting'), (5, 'standup')
) AS link(n, tag_name)
JOIN tags t ON t.user_id = '00000000-0000-4000-8000-000000000001' AND t.name = link.tag_name
ON CONFLICT (transcript_id, tag_id) DO NOTHING;
