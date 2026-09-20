import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../models/speaker.dart';
import '../../state/transcription_detail_controller.dart';
import '../../theme/app_palette.dart';
import '../../widgets/dashed_border.dart';
import 'ghost_actions.dart';

/// "Speakers: ● Alexei ● Maria ● James" — one chip per diarizer slot, showing
/// who the slot is mapped to (`.d-spk-row`). A slot with a pending AI
/// suggestion shows `✨ Speaker 2 → ● Maria Chen ✓ ✗` instead.
///
/// As in the design it follows the slot mapping only, not the assignments made
/// per segment in the Speaker Picker.
class DetailSpeakersRow extends StatelessWidget {
  const DetailSpeakersRow({super.key});

  /// Slot fallback of this row; the transcript uses a lighter grey (`#aaa`).
  static const _unassignedColor = Color(0xFF888888);

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final controller = context.watch<TranscriptionDetailController>();
    final detail = controller.current;
    final count = detail?.item.speakerCount ?? 0;
    if (detail == null || count == 0) return const SizedBox.shrink();
    final pending = detail.suggestions?.speakers ?? const <int, Speaker>{};

    return Wrap(
      spacing: 6,
      runSpacing: 6,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Text('Speakers:', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: p.text3)),
        for (var slot = 1; slot <= count; slot++)
          if (pending[slot] case final suggested?)
            _GhostSpeakerChip(
              slot: slot,
              suggested: suggested,
              acceptBusy: controller.isBusy(SuggestionAction.acceptSpeaker(slot)),
              rejectBusy: controller.isBusy(SuggestionAction.rejectSpeaker(slot)),
              onAccept: () => controller.acceptSpeakerSuggestion(slot),
              onReject: () => controller.rejectSpeakerSuggestion(slot),
            )
          else
            _SpeakerChip(speaker: detail.speakers[slot] ?? Speaker('Speaker $slot', _unassignedColor)),
      ],
    );
  }
}

class _SpeakerChip extends StatelessWidget {
  const _SpeakerChip({required this.speaker});

  final Speaker speaker;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 2),
      decoration: BoxDecoration(
        color: p.background,
        borderRadius: BorderRadius.circular(100),
        border: Border.all(color: p.border),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(width: 7, height: 7, decoration: BoxDecoration(color: speaker.color, shape: BoxShape.circle)),
          const SizedBox(width: 5),
          Text(
            speaker.name,
            style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: p.text2),
          ),
        ],
      ),
    );
  }
}

/// A speaker the AI recognized for a slot, waiting to be accepted (`.ghost-spk-chip`).
class _GhostSpeakerChip extends StatelessWidget {
  const _GhostSpeakerChip({
    required this.slot,
    required this.suggested,
    required this.acceptBusy,
    required this.rejectBusy,
    required this.onAccept,
    required this.onReject,
  });

  final int slot;
  final Speaker suggested;
  final bool acceptBusy;
  final bool rejectBusy;
  final VoidCallback onAccept;
  final VoidCallback onReject;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    const gap = SizedBox(width: 4);
    return DashedBorder(
      color: p.border,
      width: 1.5,
      dash: 4.5,
      gap: 3,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(7, 2, 4, 2),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Tooltip(
              message: 'AI suggestion',
              child: Icon(Icons.auto_awesome_rounded, size: 13, color: p.accent),
            ),
            gap,
            Text('Speaker $slot', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: p.text3)),
            gap,
            Icon(Icons.arrow_forward_rounded, size: 13, color: p.text3),
            gap,
            Container(width: 7, height: 7, decoration: BoxDecoration(color: suggested.color, shape: BoxShape.circle)),
            gap,
            Text(suggested.name, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: p.text)),
            gap,
            GhostAction.accept(busy: acceptBusy, onTap: onAccept),
            gap,
            GhostAction.reject(busy: rejectBusy, onTap: onReject),
          ],
        ),
      ),
    );
  }
}
