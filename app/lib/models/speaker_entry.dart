import 'speaker.dart';

/// A speaker of the library as the Speakers page shows it: the speaker, who
/// they are and where they were heard.
///
/// Like [TagEntry], this is more than the gateway returns today
/// (`docs/api-gaps.md` §2.2): the card also lists a couple of titles.
class SpeakerEntry {
  const SpeakerEntry({
    required this.speaker,
    required this.description,
    required this.transcriptions,
    required this.totalDuration,
  });

  final Speaker speaker;

  /// Role and topics; the AI uses it to recognise the voice in new transcriptions.
  final String description;

  /// Titles of the transcriptions the speaker takes part in, oldest first.
  final List<String> transcriptions;

  /// Summed length of those transcriptions (the design shows speaking time,
  /// which needs per-segment data the mock does not have).
  final Duration totalDuration;

  int get count => transcriptions.length;
}
