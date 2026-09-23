import 'tag.dart';

/// A tag of the library as the Tags page shows it: the tag itself, what it is
/// for and where it is used.
class TagEntry {
  const TagEntry({required this.tag, required this.description, required this.transcriptions, int? count})
      : _count = count;

  final Tag tag;

  /// What the tag is for; the AI uses it to tag new transcriptions.
  final String description;

  /// Titles of the transcriptions that carry the tag: only the latest couple, newest first, so the card does
  /// not depend on how many transcriptions there are ([count] has the total).
  final List<String> transcriptions;

  final int? _count;

  /// How many transcriptions carry the tag.
  int get count => _count ?? transcriptions.length;
}

/// The whole tag library, for the Tags page.
class TagLibrary {
  const TagLibrary({required this.entries, required this.taggedTranscriptions});

  /// In creation order.
  final List<TagEntry> entries;

  /// How many distinct transcriptions carry at least one tag (a transcription with two tags counts once).
  final int taggedTranscriptions;
}
