-- V24 moved the audio length from transcripts.audio_duration_s to audio_meta.duration_s, but only created the new
-- column and dropped the old one: the values were never copied, so every transcription made before that release
-- has no length (the page showed "0 min").
--
-- The true length of those files is gone with the dropped column. The end of the last phrase is the best thing
-- that is left: on the recordings that have both it is a few seconds short of the real length on average (the
-- trailing silence), which is invisible at the minute precision the screens show.
--
-- Only rows without a length are touched, so a length the transcriber reported is never overwritten, and a
-- transcription with no phrases stays unknown (the screens show nothing for it instead of "0 min").
UPDATE audio_meta a
SET duration_s = last_phrase.end_s
FROM submissions s
JOIN transcripts t ON t.submission_id = s.id
JOIN (SELECT transcript_id, max(end_s) AS end_s FROM transcript_segments GROUP BY transcript_id) last_phrase
     ON last_phrase.transcript_id = t.id
WHERE s.audio_id = a.id
  AND a.duration_s IS NULL
  AND last_phrase.end_s > 0;
