import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../models/speaker.dart';
import '../../state/transcription_detail_controller.dart';
import '../../theme/app_palette.dart';
import '../../widgets/dashed_border.dart';
import 'ghost_actions.dart';

/// "Speakers: ● Alexei ● Maria ● James" — one chip per speaker actually heard
/// in the transcript right now (`.d-spk-row`), so a reassignment in the
/// Speaker Picker — one phrase or a whole voice — adds, merges or removes a
/// chip immediately, same as it changes the transcript body below. A voice
/// with a pending AI suggestion shows `✨ Speaker 2 → ● Maria Chen ✓ ✗` instead
/// of its current name, until the suggestion is accepted or rejected.
class DetailSpeakersRow extends StatelessWidget {
  const DetailSpeakersRow({super.key});

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final controller = context.watch<TranscriptionDetailController>();
    final detail = controller.current;
    if (detail == null || detail.item.speakerCount == 0) return const SizedBox.shrink();
    final pending = detail.suggestions?.speakers ?? const <int, Speaker>{};

    return Wrap(
      spacing: 6,
      runSpacing: 6,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Text('Speakers:', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: p.text3)),
        for (final slot in pending.keys.toList()..sort())
          _GhostSpeakerChip(
            slot: slot,
            suggested: pending[slot]!,
            acceptBusy: controller.isBusy(SuggestionAction.acceptSpeaker(slot)),
            rejectBusy: controller.isBusy(SuggestionAction.rejectSpeaker(slot)),
            onAccept: () => controller.acceptSpeakerSuggestion(slot),
            onReject: () => controller.rejectSpeakerSuggestion(slot),
          ),
        // A segment of a slot with a pending suggestion still shows its "Speaker N" placeholder (the ghost chip
        // above already covers that slot), so that placeholder name is skipped here — but a real name a segment
        // of that same slot was separately given by hand still shows normally.
        for (final speaker in detail.presentSpeakers)
          if (!pending.keys.any((slot) => speaker.name == Speaker.unassigned(slot).name)) _SpeakerChip(speaker: speaker),
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
