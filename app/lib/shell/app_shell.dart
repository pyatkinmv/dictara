import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../pages/upload/upload_dialog.dart';
import '../services/file_chooser.dart';
import '../state/transcription_detail_controller.dart';
import '../state/transcription_list_controller.dart';
import 'nav_destinations.dart';
import 'sidebar.dart';
import 'topbar.dart';

/// Sidebar + topbar frame around the current page.
class AppShell extends StatelessWidget {
  const AppShell({super.key, required this.location, required this.child});

  /// Current route path.
  final String location;
  final Widget child;

  /// The topbar's Upload button: pick a file and options, send it, and show
  /// the list, where the new transcription appears.
  Future<void> _upload(BuildContext context) async {
    final list = context.read<TranscriptionListController>();
    final request = await showUploadDialog(context, chooser: context.read<FileChooser>());
    if (request == null) return;
    await list.upload(request);
    if (context.mounted) context.go('/transcriptions');
  }

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
                Topbar(title: title, onUpload: () => _upload(context)),
                Expanded(child: child),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
