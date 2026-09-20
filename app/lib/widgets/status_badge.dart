import 'package:flutter/material.dart';

import '../models/transcription.dart';
import '../theme/app_palette.dart';

extension TranscriptionStatusStyle on TranscriptionStatus {
  IconData get icon => switch (this) {
        TranscriptionStatus.done => Icons.check_circle_rounded,
        TranscriptionStatus.processing => Icons.autorenew_rounded,
        TranscriptionStatus.pending => Icons.schedule_rounded,
      };

  String get label => switch (this) {
        TranscriptionStatus.done => 'Done',
        TranscriptionStatus.processing => 'Processing',
        TranscriptionStatus.pending => 'Pending',
      };

  Color foreground(AppPalette p) => switch (this) {
        TranscriptionStatus.done => p.ok,
        TranscriptionStatus.processing => p.warn,
        TranscriptionStatus.pending => p.text3,
      };

  Color background(AppPalette p) => switch (this) {
        TranscriptionStatus.done => p.okBg,
        TranscriptionStatus.processing => p.warnBg,
        TranscriptionStatus.pending => p.background,
      };
}

/// "Done" / "Processing" / "Pending" pill (`.badge`).
class StatusBadge extends StatelessWidget {
  const StatusBadge(this.status, {super.key});

  final TranscriptionStatus status;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      // Chrome rounds this line box up to 18 px; Flutter keeps 17.3.
      constraints: const BoxConstraints(minHeight: 18),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(color: status.background(p), borderRadius: BorderRadius.circular(100)),
      child: Text(
        status.label,
        style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: status.foreground(p)),
      ),
    );
  }
}
