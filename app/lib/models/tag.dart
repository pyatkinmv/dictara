import 'package:flutter/painting.dart';

/// A label from the tag library.
///
/// TODO: `docs/design.md` gives a tag a stored `color`, while `docs/api-gaps.md`
/// derives it from the label on the client. Undecided; the mocks carry a color.
///
/// TODO: the gateway removes a tag from a transcription by id
/// (`DELETE /jobs/{id}/tags/{tagId}`, api-gaps §2.3), so the model needs an id
/// once the API is wired in; until then the label identifies a tag.
class Tag {
  const Tag(this.label, this.color);

  final String label;
  final Color color;

  @override
  bool operator ==(Object other) => other is Tag && other.label == label;

  @override
  int get hashCode => label.hashCode;
}

/// Colors handed to new tags and speakers in turn (`docs/design.md`: the
/// color at index `library.size % 10`).
const libraryPalette = <Color>[
  Color(0xFF2563EB),
  Color(0xFF059669),
  Color(0xFF7C3AED),
  Color(0xFFD97706),
  Color(0xFF0891B2),
  Color(0xFF6366F1),
  Color(0xFFEF4444),
  Color(0xFFEC4899),
  Color(0xFF0D9488),
  Color(0xFFF59E0B),
];
