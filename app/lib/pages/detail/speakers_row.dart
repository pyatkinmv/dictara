import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../models/speaker.dart';
import '../../state/transcription_detail_controller.dart';
import '../../theme/app_palette.dart';

/// "Speakers: ● Alexei ● Maria ● James" — one chip per diarizer slot, showing
/// who the slot is mapped to (`.d-spk-row`).
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
    final detail = context.watch<TranscriptionDetailController>().current;
    final count = detail?.item.speakerCount ?? 0;
    if (detail == null || count == 0) return const SizedBox.shrink();

    return Wrap(
      spacing: 6,
      runSpacing: 6,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Text('Speakers:', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: p.text3)),
        for (var slot = 1; slot <= count; slot++)
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
