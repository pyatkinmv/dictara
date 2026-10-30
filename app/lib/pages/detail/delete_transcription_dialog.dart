import 'package:flutter/material.dart';

import '../../theme/app_palette.dart';
import '../../widgets/modal.dart';

/// "Delete "Budget review"?": says what goes (the transcript, the summary, the audio file, the tags and speakers on
/// it), what stays (the libraries; copies the user already has), and that it cannot be undone. Returns true if the
/// user confirmed.
Future<bool> showDeleteTranscriptionDialog(BuildContext context, {required String title}) async {
  final confirmed = await showAppModal<bool>(context, builder: (context) => _DeleteTranscriptionDialog(title: title));
  return confirmed ?? false;
}

class _DeleteTranscriptionDialog extends StatelessWidget {
  const _DeleteTranscriptionDialog({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return ModalCard(
      width: 380,
      radius: 16,
      shadow: const BoxShadow(color: Color(0x26000000), blurRadius: 40, offset: Offset(0, 12)),
      child: Column(
        key: const Key('delete-transcription-dialog'),
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
          Text(
            'Delete "$title"?',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 6),
          Text(
            'This permanently removes the transcript, the summary and the audio file, and takes the tags and speakers off it.',
            style: TextStyle(fontSize: 14, height: 1.55, color: p.text2),
          ),
          const SizedBox(height: 12),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 1),
                child: Icon(Icons.info_outline_rounded, size: 16, color: p.accent),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  'Your tags and speakers stay in the library. Copies you already downloaded or got in Telegram are not touched.',
                  style: TextStyle(fontSize: 12.5, height: 1.5, color: p.text2),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
            decoration: BoxDecoration(color: p.dangerBg, borderRadius: BorderRadius.circular(9)),
            child: Text(
              "This can't be undone.",
              style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: p.danger),
            ),
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
                label: 'Delete forever',
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
