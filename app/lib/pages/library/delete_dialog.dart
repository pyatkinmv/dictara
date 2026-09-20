import 'package:flutter/material.dart';

import '../../theme/app_palette.dart';
import '../../widgets/modal.dart';

/// "Delete "meeting"? This tag will be removed from 3 transcriptions. This
/// cannot be undone." (`.del-dialog`). Returns true if the user confirmed.
Future<bool> showDeleteDialog(
  BuildContext context, {
  required String name,
  required String kind,
  required int transcriptions,
}) async {
  final confirmed = await showAppModal<bool>(
    context,
    builder: (context) => _DeleteDialog(name: name, kind: kind, transcriptions: transcriptions),
  );
  return confirmed ?? false;
}

class _DeleteDialog extends StatelessWidget {
  const _DeleteDialog({required this.name, required this.kind, required this.transcriptions});

  final String name;
  final String kind;
  final int transcriptions;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    // The design writes "transcription(s)"; use the right plural instead.
    final count = '$transcriptions transcription${transcriptions == 1 ? '' : 's'}';
    return ModalCard(
      width: 360,
      radius: 16,
      shadow: const BoxShadow(color: Color(0x26000000), blurRadius: 40, offset: Offset(0, 12)),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Align(
            alignment: Alignment.centerLeft,
            child: Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(color: p.dangerBg, shape: BoxShape.circle),
              child: Icon(Icons.delete_rounded, size: 20, color: p.danger),
            ),
          ),
          const SizedBox(height: 14),
          Text('Delete "$name"?', style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
          const SizedBox(height: 6),
          Text.rich(
            TextSpan(
              children: [
                TextSpan(text: 'This $kind will be removed from '),
                TextSpan(text: count, style: const TextStyle(fontWeight: FontWeight.w700)),
                const TextSpan(text: '. This cannot be undone.'),
              ],
            ),
            style: TextStyle(fontSize: 14, height: 1.55, color: p.text2),
          ),
          const SizedBox(height: 20),
          ModalFooter(
            buttons: [
              ModalButton(
                label: 'Cancel',
                kind: ModalButtonKind.cancel,
                onPressed: () => Navigator.of(context).pop(false),
              ),
              ModalButton(
                label: 'Delete',
                kind: ModalButtonKind.danger,
                onPressed: () => Navigator.of(context).pop(true),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
