import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../theme/app_palette.dart';

/// "Copy" that puts [text] on the clipboard and says "Copied" for a moment.
class CopyButton extends StatefulWidget {
  const CopyButton({super.key, required this.text, this.label = 'Copy'});

  final String text;
  final String label;

  @override
  State<CopyButton> createState() => _CopyButtonState();
}

class _CopyButtonState extends State<CopyButton> {
  bool _hover = false;
  bool _copied = false;
  Timer? _reset;

  @override
  void dispose() {
    _reset?.cancel();
    super.dispose();
  }

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: widget.text));
    if (!mounted) return;
    setState(() => _copied = true);
    _reset?.cancel();
    _reset = Timer(const Duration(milliseconds: 1600), () {
      if (mounted) setState(() => _copied = false);
    });
  }

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: _copy,
        behavior: HitTestBehavior.opaque,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
          decoration: BoxDecoration(
            color: _hover ? p.accentLight : Colors.transparent,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: _copied ? p.ok : p.border),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(_copied ? Icons.check_rounded : Icons.copy_rounded, size: 14, color: _copied ? p.ok : p.text2),
              const SizedBox(width: 5),
              Text(
                _copied ? 'Copied' : widget.label,
                style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: _copied ? p.ok : p.text2),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// A block of selectable monospace text (an address, a token, a command) with a Copy button.
class CopyBlock extends StatelessWidget {
  const CopyBlock({super.key, required this.text, this.copyLabel = 'Copy', this.multiline = false, this.blockKey});

  final String text;
  final String copyLabel;

  /// A command or a config over several lines: kept as it is, the copy button under it.
  final bool multiline;
  final Key? blockKey;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final body = SelectableText(
      text,
      key: blockKey,
      style: TextStyle(fontFamily: 'monospace', fontSize: 12, height: 1.5, color: p.text),
    );
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 9, 9, 9),
      decoration: BoxDecoration(
        color: p.background,
        borderRadius: BorderRadius.circular(9),
        border: Border.all(color: p.border),
      ),
      child: multiline
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                body,
                const SizedBox(height: 8),
                Align(alignment: Alignment.centerRight, child: CopyButton(text: text, label: copyLabel)),
              ],
            )
          : Row(
              children: [
                Expanded(child: body),
                const SizedBox(width: 10),
                CopyButton(text: text, label: copyLabel),
              ],
            ),
    );
  }
}
