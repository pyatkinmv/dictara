import 'package:flutter/painting.dart';

/// A named speaker from the speaker library.
///
/// Only what the transcript needs so far; the Speakers page (iteration 9) adds
/// the description and usage stats from `docs/design.md`.
class Speaker {
  const Speaker(this.name, this.color);

  final String name;
  final Color color;

  /// Shown for a diarizer slot nobody has been assigned to (`Speaker 2`).
  factory Speaker.unassigned(int slot) => Speaker('Speaker $slot', const Color(0xFFAAAAAA));

  /// Up to two capitals from the first letters of the words: "Maria Chen" → "MC".
  String get initials {
    final letters = name.trim().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).map((w) => w[0]);
    final text = letters.join();
    return (text.length > 2 ? text.substring(0, 2) : text).toUpperCase();
  }

  @override
  bool operator ==(Object other) => other is Speaker && other.name == name && other.color == color;

  @override
  int get hashCode => Object.hash(name, color);
}
