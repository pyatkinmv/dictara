import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../../shell/breakpoints.dart';
import '../../state/transcription_detail_controller.dart';
import '../../theme/app_palette.dart';
import '../../widgets/button_spinner.dart';
import '../../widgets/error_reporter.dart';

/// The text of one phrase, and the way to correct it right where it stands: a pencil at the end of the text (on hover;
/// always on a phone, which has no pointer) turns it into a field. Enter saves, Esc cancels, Shift+Enter starts a new
/// line; the check and the cross do the same for a touch screen. An empty text is not saved. Only the text changes:
/// the phrase keeps its time and its speaker, and nothing else about the transcription is worked out again.
class PhraseText extends StatefulWidget {
  const PhraseText({super.key, required this.index, required this.text, required this.style, required this.rowHovered});

  /// Position of the phrase in the whole transcript, from 0.
  final int index;
  final String text;

  /// How the text is drawn (its color follows playback); the field uses the same size and weight.
  final TextStyle style;

  /// The pointer is on the phrase's row: the pencil shows.
  final bool rowHovered;

  @override
  State<PhraseText> createState() => _PhraseTextState();
}

class _PhraseTextState extends State<PhraseText> {
  final _controller = TextEditingController();
  final _focus = FocusNode();
  bool _editing = false;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  void _start() {
    _controller.text = widget.text;
    _controller.selection = TextSelection.collapsed(offset: _controller.text.length);
    setState(() {
      _editing = true;
      _error = null;
    });
  }

  void _cancel() {
    if (_saving) return;
    setState(() => _editing = false);
  }

  Future<void> _save() async {
    if (_saving) return;
    final text = _controller.text.trim();
    if (text.isEmpty) {
      setState(() => _error = "A phrase can't be empty");
      return;
    }
    if (text == widget.text) {
      setState(() => _editing = false);
      return;
    }
    final controller = context.read<TranscriptionDetailController>();
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await controller.editPhrase(widget.index, text);
      if (!mounted) return;
      setState(() {
        _saving = false;
        _editing = false;
      });
    } on Object catch (error, stack) {
      debugPrint('Could not save the phrase: $error\n$stack');
      if (!mounted) return;
      // The field stays open with what was typed, so that nothing is lost.
      setState(() {
        _saving = false;
        _error = ErrorReporter.messageFor(error);
      });
    }
  }

  KeyEventResult _onKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent) return KeyEventResult.ignored;
    final key = event.logicalKey;
    if (key == LogicalKeyboardKey.escape) {
      _cancel();
      return KeyEventResult.handled;
    }
    if (key == LogicalKeyboardKey.enter || key == LogicalKeyboardKey.numpadEnter) {
      // Enter that picks a word of an input method is not a "save".
      final composing = _controller.value.composing;
      if (composing.isValid && !composing.isCollapsed) return KeyEventResult.ignored;
      if (HardwareKeyboard.instance.isShiftPressed) return KeyEventResult.ignored; // a new line
      _save();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    if (!_editing) {
      final visible = widget.rowHovered || isMobileWidth(context);
      // A plain Text (not rich text with the pencil inside it): the selection and everything that finds the text by its
      // content keep working as before. The pencil follows the text when it is short and sits at the end of the row when
      // the text fills it, centered on the first line.
      final lineHeight = (widget.style.fontSize ?? 14) * (widget.style.height ?? 1.4);
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Flexible(child: Text(widget.text, style: widget.style)),
          Padding(
            padding: const EdgeInsets.only(left: 6),
            child: SizedBox(
              height: lineHeight,
              child: IgnorePointer(
                ignoring: !visible,
                child: AnimatedOpacity(
                  duration: const Duration(milliseconds: 120),
                  opacity: visible ? 1 : 0,
                  child: Tooltip(
                    message: 'Edit text',
                    waitDuration: const Duration(milliseconds: 600),
                    child: MouseRegion(
                      cursor: SystemMouseCursors.click,
                      child: GestureDetector(
                        key: Key('edit-phrase-${widget.index}'),
                        onTap: _start,
                        behavior: HitTestBehavior.opaque,
                        child: Center(child: Icon(Icons.edit_rounded, size: 13, color: p.text3)),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      );
    }

    final border = OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: BorderSide(color: p.accent, width: 1.5));
    return SelectionContainer.disabled(
      child: Column(
        key: Key('phrase-editor-${widget.index}'),
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Focus(
                  onKeyEvent: _onKey,
                  child: TextField(
                    key: Key('phrase-field-${widget.index}'),
                    controller: _controller,
                    focusNode: _focus,
                    autofocus: true,
                    enabled: !_saving,
                    minLines: 1,
                    maxLines: null,
                    keyboardType: TextInputType.multiline,
                    cursorColor: p.accent,
                    style: widget.style.copyWith(color: p.text, fontWeight: FontWeight.w400),
                    decoration: InputDecoration(
                      isDense: true,
                      filled: true,
                      fillColor: p.surface,
                      contentPadding: const EdgeInsets.symmetric(horizontal: 9, vertical: 7),
                      border: border,
                      enabledBorder: border,
                      focusedBorder: border,
                      disabledBorder: border,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Padding(
                padding: const EdgeInsets.only(top: 7),
                child: _saving
                    ? SizedBox(width: 38, child: Center(child: ButtonSpinner(color: p.accent, size: 14)))
                    : Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          _IconAction(key: const Key('save-phrase'), icon: Icons.check_circle_rounded, color: p.accent, tooltip: 'Save', onTap: _save),
                          const SizedBox(width: 6),
                          _IconAction(key: const Key('cancel-phrase'), icon: Icons.close_rounded, color: p.text3, tooltip: 'Cancel', onTap: _cancel),
                        ],
                      ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            _error ?? 'Enter to save · Esc to cancel · Shift+Enter for a new line',
            style: TextStyle(fontSize: 11.5, height: 1.4, color: _error == null ? p.text3 : p.danger),
          ),
          const SizedBox(height: 2),
        ],
      ),
    );
  }
}

class _IconAction extends StatelessWidget {
  const _IconAction({super.key, required this.icon, required this.color, required this.tooltip, required this.onTap});

  final IconData icon;
  final Color color;
  final String tooltip;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: MouseRegion(
        cursor: SystemMouseCursors.click,
        child: GestureDetector(
          onTap: onTap,
          behavior: HitTestBehavior.opaque,
          child: SizedBox(width: 16, height: 16, child: Center(child: Icon(icon, size: 16, color: color))),
        ),
      ),
    );
  }
}
