import 'package:flutter/material.dart';

import '../../theme/app_palette.dart';
import '../../widgets/modal.dart';

/// "Regenerate the summary?": says what will happen, and how many times it can still be done on the user's plan
/// ([left]: null when there is no limit), before a regeneration is spent. Returns true if the user confirmed.
Future<bool> showRegenerateDialog(BuildContext context, {int? left}) async {
  final confirmed = await showAppModal<bool>(context, builder: (context) => _RegenerateDialog(left: left));
  return confirmed ?? false;
}

class _RegenerateDialog extends StatelessWidget {
  const _RegenerateDialog({required this.left});

  final int? left;

  String get _limit => switch (left) {
        null => '',
        1 => ' You can do this once for this recording.',
        final n => ' You can do this $n more times for this recording.',
      };

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return ModalCard(
      width: 380,
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
              decoration: BoxDecoration(color: p.accentLight, shape: BoxShape.circle),
              child: Icon(Icons.refresh_rounded, size: 20, color: p.accent),
            ),
          ),
          const SizedBox(height: 14),
          const Text('Regenerate the summary?', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
          const SizedBox(height: 6),
          Text(
            'The summary is replaced, and a new title, tags and speakers are suggested. '
            'Anything you accepted or rejected stays.$_limit',
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
                label: 'Regenerate',
                onPressed: () => Navigator.of(context).pop(true),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
