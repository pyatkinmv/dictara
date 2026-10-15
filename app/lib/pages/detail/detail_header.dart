import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../models/transcription_detail.dart';
import '../../services/text_saver.dart';
import '../../state/transcription_detail_controller.dart';
import '../../theme/app_palette.dart';
import '../../util/format.dart';
import '../../widgets/status_badge.dart';
import 'speakers_row.dart';
import 'suggestions_banner.dart';
import 'tags_row.dart';

/// Sticky top part of the detail page: breadcrumb, editable title, meta row
/// and the action buttons (`.detail-header`).
class DetailHeader extends StatelessWidget {
  const DetailHeader({super.key, required this.detail});

  final TranscriptionDetail detail;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final item = detail.item;
    return Container(
      padding: const EdgeInsets.fromLTRB(24, 14, 24, 14),
      decoration: BoxDecoration(
        color: p.surface,
        border: Border(bottom: BorderSide(color: p.border)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _Breadcrumb(title: item.title),
          const SizedBox(height: 10),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _TitleEditor(title: item.title),
                    const SuggestionsBanner(),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(6, 0, 6, 10),
                      child: _MetaRow(detail: detail),
                    ),
                    const Padding(
                      padding: EdgeInsets.fromLTRB(6, 0, 6, 10),
                      child: DetailTagsRow(),
                    ),
                    const Padding(
                      padding: EdgeInsets.fromLTRB(6, 0, 6, 6),
                      child: DetailSpeakersRow(),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 16),
              Row(
                children: [
                  _HeaderButton(
                    icon: Icons.download_rounded,
                    label: '.txt',
                    doneLabel: 'Saved',
                    onTap: () => _download(context),
                  ),
                  const SizedBox(width: 7),
                  _HeaderButton(
                    icon: Icons.content_copy_rounded,
                    label: 'Copy',
                    doneLabel: 'Copied',
                    onTap: () => _copy(context),
                  ),
                ],
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// The transcript as a `.txt` file named after the title.
  static Future<void> _download(BuildContext context) async {
    final controller = context.read<TranscriptionDetailController>();
    final saver = context.read<TextSaver>();
    final title = controller.current?.item.title ?? '';
    final text = await controller.transcriptText();
    if (text == null) throw StateError('There is no transcript');
    await saver.save(transcriptFileName(title), text);
  }

  static Future<void> _copy(BuildContext context) async {
    final text = await context.read<TranscriptionDetailController>().transcriptText();
    if (text == null) throw StateError('There is no transcript');
    await Clipboard.setData(ClipboardData(text: text));
  }
}

class _Breadcrumb extends StatefulWidget {
  const _Breadcrumb({required this.title});

  final String title;

  @override
  State<_Breadcrumb> createState() => _BreadcrumbState();
}

class _BreadcrumbState extends State<_Breadcrumb> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Row(
      children: [
        MouseRegion(
          cursor: SystemMouseCursors.click,
          onEnter: (_) => setState(() => _hover = true),
          onExit: (_) => setState(() => _hover = false),
          child: GestureDetector(
            onTap: () => context.go('/transcriptions'),
            child: Text(
              'Transcriptions',
              style: TextStyle(
                fontSize: 13,
                color: p.accent,
                decoration: _hover ? TextDecoration.underline : TextDecoration.none,
                decorationColor: p.accent,
              ),
            ),
          ),
        ),
        const SizedBox(width: 4),
        Icon(Icons.chevron_right_rounded, size: 16, color: p.text3),
        const SizedBox(width: 4),
        Flexible(
          child: Text(
            widget.title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: 13, color: p.text3),
          ),
        ),
      ],
    );
  }
}

/// The title with a rename pencil on hover; turns into an input with save and
/// cancel buttons while editing (Enter saves, Escape cancels).
class _TitleEditor extends StatefulWidget {
  const _TitleEditor({required this.title});

  final String title;

  @override
  State<_TitleEditor> createState() => _TitleEditorState();
}

class _TitleEditorState extends State<_TitleEditor> {
  final _input = TextEditingController();
  final _focus = FocusNode();
  bool _editing = false;
  bool _hover = false;

  @override
  void dispose() {
    _input.dispose();
    _focus.dispose();
    super.dispose();
  }

  void _startEditing() {
    _input.text = widget.title;
    setState(() => _editing = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _focus.requestFocus();
      _input.selection = TextSelection(baseOffset: 0, extentOffset: _input.text.length);
    });
  }

  void _save() {
    context.read<TranscriptionDetailController>().rename(_input.text);
    setState(() => _editing = false);
  }

  void _cancel() => setState(() => _editing = false);

  @override
  Widget build(BuildContext context) {
    return _editing ? _buildEditing(context) : _buildTitle(context);
  }

  Widget _buildTitle(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Row(
          children: [
            Flexible(
              child: Text(
                widget.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, letterSpacing: -.4),
              ),
            ),
            const SizedBox(width: 6),
            AnimatedOpacity(
              duration: const Duration(milliseconds: 150),
              opacity: _hover ? 1 : 0,
              child: _IconButton(
                size: 26,
                radius: 6,
                icon: Icons.edit_rounded,
                iconSize: 16,
                color: p.text3,
                hoverColor: p.accent,
                hoverBackground: p.background,
                tooltip: 'Rename',
                onTap: _startEditing,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEditing(BuildContext context) {
    final p = context.palette;
    OutlineInputBorder border(Color color) => OutlineInputBorder(
      borderRadius: BorderRadius.circular(8),
      borderSide: BorderSide(color: color, width: 1.5),
    );
    return Padding(
      padding: const EdgeInsets.only(bottom: 5),
      child: Row(
        children: [
          Expanded(
            child: CallbackShortcuts(
              bindings: {const SingleActivator(LogicalKeyboardKey.escape): _cancel},
              // 31 px like the design's input (padding 4 + line + 1.5 px border).
              child: SizedBox(
                height: 31,
                child: TextField(
                  controller: _input,
                  focusNode: _focus,
                  onSubmitted: (_) => _save(),
                  cursorColor: p.accent,
                  textAlignVertical: TextAlignVertical.center,
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, letterSpacing: -.3),
                  decoration: InputDecoration(
                    isDense: true,
                    filled: true,
                    fillColor: p.surface,
                    hintText: 'Title…',
                    contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                    border: border(p.border),
                    enabledBorder: border(p.border),
                    focusedBorder: border(p.accent),
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(width: 6),
          _IconButton(
            size: 30,
            radius: 7,
            icon: Icons.check_rounded,
            iconSize: 16,
            color: Colors.white,
            hoverColor: Colors.white,
            background: p.accent,
            hoverBackground: p.accentDark,
            borderColor: p.accent,
            hoverBorderColor: p.accentDark,
            tooltip: 'Save',
            onTap: _save,
          ),
          const SizedBox(width: 6),
          _IconButton(
            size: 30,
            radius: 7,
            icon: Icons.close_rounded,
            iconSize: 16,
            color: p.text3,
            hoverColor: p.text2,
            hoverBackground: p.background,
            borderColor: p.border,
            tooltip: 'Cancel',
            onTap: _cancel,
          ),
        ],
      ),
    );
  }
}

/// Small square icon button with hover colors (`.d-rename-btn`, `.d-save-btn`,
/// `.d-cancel-btn`).
class _IconButton extends StatefulWidget {
  const _IconButton({
    required this.size,
    required this.radius,
    required this.icon,
    required this.iconSize,
    required this.color,
    required this.hoverColor,
    required this.hoverBackground,
    required this.tooltip,
    required this.onTap,
    this.background,
    this.borderColor,
    this.hoverBorderColor,
  });

  final double size;
  final double radius;
  final IconData icon;
  final double iconSize;
  final Color color;
  final Color hoverColor;
  final Color? background;
  final Color hoverBackground;
  final Color? borderColor;
  final Color? hoverBorderColor;
  final String tooltip;
  final VoidCallback onTap;

  @override
  State<_IconButton> createState() => _IconButtonState();
}

class _IconButtonState extends State<_IconButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final border = _hover ? (widget.hoverBorderColor ?? widget.borderColor) : widget.borderColor;
    return Tooltip(
      message: widget.tooltip,
      child: MouseRegion(
        cursor: SystemMouseCursors.click,
        onEnter: (_) => setState(() => _hover = true),
        onExit: (_) => setState(() => _hover = false),
        child: GestureDetector(
          onTap: widget.onTap,
          child: Container(
            width: widget.size,
            height: widget.size,
            decoration: BoxDecoration(
              color: _hover ? widget.hoverBackground : widget.background,
              borderRadius: BorderRadius.circular(widget.radius),
              border: border == null ? null : Border.all(color: border, width: 1.5),
            ),
            child: Icon(widget.icon, size: widget.iconSize, color: _hover ? widget.hoverColor : widget.color),
          ),
        ),
      ),
    );
  }
}

class _MetaRow extends StatelessWidget {
  const _MetaRow({required this.detail});

  final TranscriptionDetail detail;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final item = detail.item;
    Widget entry(IconData icon, String text) => Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: p.text3),
        const SizedBox(width: 4),
        Text(text, style: TextStyle(fontSize: 13, color: p.text2)),
      ],
    );
    return Wrap(
      spacing: 10,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        // Unknown is not zero: the length of old recordings was lost in a migration, so show nothing for it (as the list does).
        if (item.duration != null) entry(Icons.schedule_rounded, formatDuration(item.duration!)),
        entry(Icons.calendar_today_rounded, formatDay(item.uploadedAt)),
        StatusBadge(item.status),
      ],
    );
  }
}

/// Outlined action button of the header (`.d-btn`). While its action runs the label stays; after it, the
/// label says for a moment how it went ("Copied", "Failed").
class _HeaderButton extends StatefulWidget {
  const _HeaderButton({required this.icon, required this.label, required this.doneLabel, required this.onTap});

  final IconData icon;
  final String label;

  /// Shown for a moment after the action succeeded.
  final String doneLabel;

  /// Does the action; throws when it failed.
  final Future<void> Function() onTap;

  @override
  State<_HeaderButton> createState() => _HeaderButtonState();
}

class _HeaderButtonState extends State<_HeaderButton> {
  bool _hover = false;
  bool _busy = false;
  String? _feedback;
  Timer? _reset;

  @override
  void dispose() {
    _reset?.cancel();
    super.dispose();
  }

  Future<void> _run() async {
    if (_busy) return;
    _busy = true;
    String feedback;
    try {
      await widget.onTap();
      feedback = widget.doneLabel;
    } on Object {
      feedback = 'Failed';
    }
    _busy = false;
    if (!mounted) return;
    setState(() => _feedback = feedback);
    _reset?.cancel();
    _reset = Timer(const Duration(milliseconds: 1600), () {
      if (mounted) setState(() => _feedback = null);
    });
  }

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final color = _hover ? p.accent : p.text2;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: _run,
        behavior: HitTestBehavior.opaque,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 6),
          decoration: BoxDecoration(
            color: _hover ? p.accentLight : p.surface,
            borderRadius: BorderRadius.circular(9),
            border: Border.all(color: _hover ? p.accent : p.border),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(widget.icon, size: 15, color: color),
              const SizedBox(width: 5),
              Text(
                _feedback ?? widget.label,
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: color),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
