import 'package:flutter/material.dart';

import '../theme/app_palette.dart';

/// Stand-in for screens that are not built yet.
class PlaceholderPage extends StatelessWidget {
  const PlaceholderPage({super.key, required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Center(
      child: Text(
        '$label — coming soon',
        style: TextStyle(fontSize: 14, color: p.text3),
      ),
    );
  }
}
