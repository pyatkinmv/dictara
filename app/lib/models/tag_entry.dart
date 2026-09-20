import 'tag.dart';

/// A tag of the library as the Tags page shows it: the tag itself, what it is
/// for and where it is used.
///
/// Today the gateway has no library endpoints (`docs/api-gaps.md` §2.1) and
/// would return only the count, while the card also lists a couple of titles.
class TagEntry {
  const TagEntry({required this.tag, required this.description, required this.transcriptions});

  final Tag tag;

  /// What the tag is for; the AI uses it to tag new transcriptions.
  final String description;

  /// Titles of the transcriptions that carry the tag, oldest first.
  final List<String> transcriptions;

  int get count => transcriptions.length;
}
