import 'package:flutter/painting.dart';

/// A label from the tag library.
///
/// TODO: `docs/design.md` gives a tag a stored `color`, while `docs/api-gaps.md`
/// derives it from the label on the client. Undecided; the mocks carry a color.
class Tag {
  const Tag(this.label, this.color);

  final String label;
  final Color color;

  @override
  bool operator ==(Object other) => other is Tag && other.label == label;

  @override
  int get hashCode => label.hashCode;
}
