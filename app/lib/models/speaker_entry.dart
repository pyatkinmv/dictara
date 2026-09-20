import 'speaker.dart';

/// A speaker of the library as the Speakers page shows it: the speaker, who
/// they are and where they were heard.
class SpeakerEntry {
  const SpeakerEntry({
    required this.speaker,
    required this.description,
    required this.transcriptions,
    required this.totalDuration,
    int? count,
  }) : _count = count;

  final Speaker speaker;

  /// Role and topics; the AI uses it to recognise the voice in new transcriptions.
  final String description;

  /// Titles of the transcriptions the speaker takes part in. All of them, oldest first, in the mock; from the
  /// gateway only the latest few, newest first ([count] has the total).
  final List<String> transcriptions;

  /// How long the speaker speaks in total. The gateway sums the length of their phrases; the mock has no
  /// per-phrase data and sums the length of the transcriptions instead.
  final Duration totalDuration;

  final int? _count;

  /// In how many transcriptions the speaker takes part.
  int get count => _count ?? transcriptions.length;
}
