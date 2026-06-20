-- audio_content is deprecated (all files migrated to GCS).
-- The FK was safe before, but the new upload-before-save ordering in TranscribeController
-- inserts audio_content before audio_meta exists. Drop the constraint; the 90-day GCS
-- lifecycle and the deprecated DatabaseAudioStorage path make it unnecessary.
ALTER TABLE audio_content DROP CONSTRAINT audio_content_audio_id_fkey;
