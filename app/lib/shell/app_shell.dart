import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../state/transcription_detail_controller.dart';
import 'nav_destinations.dart';
import 'sidebar.dart';
import 'topbar.dart';

/// Sidebar + topbar frame around the current page.
class AppShell extends StatelessWidget {
  const AppShell({super.key, required this.location, required this.child});

  /// Current route path.
  final String location;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    // On a detail page the topbar shows the transcription's own title.
    final openTitle = context.select<TranscriptionDetailController, String?>((c) => c.current?.item.title);
    final destination = destinationFor(location);
    final isDetail = destination != null && location != destination.path;
    final title = (isDetail ? openTitle : null) ?? destination?.label ?? 'Dictara';

    return Scaffold(
      body: Row(
        children: [
          Sidebar(location: location),
          Expanded(
            child: Column(
              children: [
                // The Upload modal arrives in a later iteration.
                Topbar(title: title, onUpload: () {}),
                Expanded(child: child),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
