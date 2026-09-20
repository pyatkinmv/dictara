import 'package:flutter/material.dart';

import '../models/tag.dart';

/// Colored pill with a tag label (`.mini-tag`).
class MiniTag extends StatelessWidget {
  const MiniTag(this.tag, {super.key});

  final Tag tag;

  @override
  Widget build(BuildContext context) {
    return Container(
      // Chrome rounds this line box up to 18 px; Flutter keeps 17.3.
      constraints: const BoxConstraints(minHeight: 18),
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(color: tag.color, borderRadius: BorderRadius.circular(100)),
      child: Text(
        tag.label,
        style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: Colors.white),
      ),
    );
  }
}
