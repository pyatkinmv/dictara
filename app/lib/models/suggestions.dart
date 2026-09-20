import 'speaker.dart';
import 'tag.dart';

/// What the AI proposed for a finished transcription and the user has not yet
/// accepted or rejected (`docs/design.md`, "Suggestions").
///
/// The three parts are resolved independently; once nothing is left the
/// server drops the record, so a resolved suggestion never comes back.
///
/// How the gateway produces and stores them is open (`docs/api-gaps.md` §1.2,
/// §4.2): one Gemini call or two.
class Suggestions {
  const Suggestions({this.title, this.tags = const [], this.speakers = const {}});

  /// A new name for the transcription; null once accepted or dismissed.
  final String? title;

  /// Tags to add, with the colors the AI proposed for them.
  final List<Tag> tags;

  /// Diarizer slot → the speaker the AI recognized there.
  final Map<int, Speaker> speakers;

  bool get isEmpty => title == null && tags.isEmpty && speakers.isEmpty;

  Suggestions copyWith({
    bool clearTitle = false,
    List<Tag>? tags,
    Map<int, Speaker>? speakers,
  }) {
    return Suggestions(
      title: clearTitle ? null : title,
      tags: tags ?? this.tags,
      speakers: speakers ?? this.speakers,
    );
  }
}
