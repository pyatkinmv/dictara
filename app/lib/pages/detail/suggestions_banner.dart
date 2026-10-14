import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../state/transcription_detail_controller.dart';
import '../../theme/app_palette.dart';
import '../../widgets/button_spinner.dart';

/// One compact row above the meta line while the AI has something to propose:
///
///   ✨ Rename to: <title> · + 3 tags · 3 speakers   Accept all   ✗
///
/// Each part shows only while it is still pending (`.sugg-banner`). The row
/// disappears once everything is resolved.
class SuggestionsBanner extends StatelessWidget {
  const SuggestionsBanner({super.key});

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final controller = context.watch<TranscriptionDetailController>();
    final s = controller.current?.suggestions;
    if (s == null || s.isEmpty) return const SizedBox.shrink();

    final hasTagsOrSpeakers = s.tags.isNotEmpty || s.speakers.isNotEmpty;
    final title = s.title;
    final parts = [
      if (s.tags.isNotEmpty) '${s.tags.length} tag${s.tags.length > 1 ? 's' : ''}',
      if (s.speakers.isNotEmpty) '${s.speakers.length} speaker${s.speakers.length > 1 ? 's' : ''}',
    ];
    TextStyle text(Color color) => TextStyle(fontSize: 12, color: color);

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      // Only as wide as its content, like `width: fit-content`.
      child: Align(
        alignment: Alignment.centerLeft,
        // Container, not DecoratedBox: the border must take space, as a CSS border does.
        child: Container(
          padding: const EdgeInsets.fromLTRB(10, 5, 6, 5),
          decoration: BoxDecoration(
            color: p.accentLight,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: p.accent.withValues(alpha: .18)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.auto_awesome_rounded, size: 14, color: p.accent),
              const SizedBox(width: 6),
              if (title != null) ...[
                Text('Rename to:', style: text(p.text3)),
                const SizedBox(width: 6),
                Flexible(
                  child: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: text(p.text2)),
                ),
                const SizedBox(width: 6),
              ],
              if (title != null && hasTagsOrSpeakers) ...[
                Text('·', style: text(p.text3)),
                const SizedBox(width: 6),
              ],
              if (hasTagsOrSpeakers) ...[
                Text('+ ${parts.join(' · ')}', style: text(p.text2)),
                const SizedBox(width: 6),
              ],
              _AcceptAll(
                busy: controller.isBusy(SuggestionAction.acceptAll),
                onTap: controller.acceptAllSuggestions,
              ),
              const SizedBox(width: 6),
              _DismissAll(
                busy: controller.isBusy(SuggestionAction.dismissAll),
                onTap: controller.dismissAllSuggestions,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AcceptAll extends StatefulWidget {
  const _AcceptAll({required this.busy, required this.onTap});

  final bool busy;
  final VoidCallback onTap;

  @override
  State<_AcceptAll> createState() => _AcceptAllState();
}

class _AcceptAllState extends State<_AcceptAll> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      cursor: widget.busy ? SystemMouseCursors.basic : SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.busy ? null : widget.onTap,
        behavior: HitTestBehavior.opaque,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
          decoration: BoxDecoration(
            color: _hover && !widget.busy ? p.accent.withValues(alpha: .10) : Colors.transparent,
            borderRadius: BorderRadius.circular(100),
          ),
          child: widget.busy
              ? ButtonSpinner(color: p.accent)
              : Text(
                  'Accept all',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: p.accent),
                ),
        ),
      ),
    );
  }
}

class _DismissAll extends StatefulWidget {
  const _DismissAll({required this.busy, required this.onTap});

  final bool busy;
  final VoidCallback onTap;

  @override
  State<_DismissAll> createState() => _DismissAllState();
}

class _DismissAllState extends State<_DismissAll> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final color = _hover ? p.text : p.text3;
    return Tooltip(
      message: 'Dismiss all',
      child: MouseRegion(
        cursor: widget.busy ? SystemMouseCursors.basic : SystemMouseCursors.click,
        onEnter: (_) => setState(() => _hover = true),
        onExit: (_) => setState(() => _hover = false),
        child: GestureDetector(
          onTap: widget.busy ? null : widget.onTap,
          behavior: HitTestBehavior.opaque,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
            child: widget.busy
                ? ButtonSpinner(color: color)
                : Icon(Icons.close_rounded, size: 15, color: color),
          ),
        ),
      ),
    );
  }
}
