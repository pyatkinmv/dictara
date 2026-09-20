import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../../models/tag.dart';
import '../../state/transcription_detail_controller.dart';
import '../../theme/app_palette.dart';
import '../../widgets/dashed_border.dart';
import '../../widgets/mini_tag.dart';
import '../../widgets/popover.dart';
import 'ghost_actions.dart';

/// The tags of the open transcription with a remove button on each, followed
/// by the "Add tag" dropdown (`.d-tags-row`).
class DetailTagsRow extends StatelessWidget {
  const DetailTagsRow({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<TranscriptionDetailController>();
    final tags = controller.current?.item.tags ?? const <Tag>[];
    final pending = controller.current?.suggestions?.tags ?? const <Tag>[];
    return Wrap(
      spacing: 5,
      runSpacing: 5,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        for (final tag in tags) _TagPill(tag: tag, onRemove: () => controller.removeTag(tag.label)),
        for (final tag in pending)
          _GhostTagPill(
            tag: tag,
            acceptBusy: controller.isBusy(SuggestionAction.acceptTag(tag.label)),
            rejectBusy: controller.isBusy(SuggestionAction.rejectTag(tag.label)),
            onAccept: () => controller.acceptTagSuggestion(tag.label),
            onReject: () => controller.rejectTagSuggestion(tag.label),
          ),
        _AddTag(
          library: controller.library,
          used: tags,
          onAdd: controller.addTag,
        ),
      ],
    );
  }
}

/// A colored tag with a small × that appears on hover (`.tag-pill`).
class _TagPill extends StatefulWidget {
  const _TagPill({required this.tag, required this.onRemove});

  final Tag tag;
  final VoidCallback onRemove;

  @override
  State<_TagPill> createState() => _TagPillState();
}

class _TagPillState extends State<_TagPill> {
  bool _hover = false;
  bool _overRemove = false;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: Container(
        padding: const EdgeInsets.fromLTRB(10, 2, 3, 2),
        decoration: BoxDecoration(color: widget.tag.color, borderRadius: BorderRadius.circular(100)),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              widget.tag.label,
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: Colors.white),
            ),
            const SizedBox(width: 2),
            AnimatedOpacity(
              duration: const Duration(milliseconds: 150),
              opacity: _hover ? (_overRemove ? 1 : .8) : 0,
              child: MouseRegion(
                cursor: SystemMouseCursors.click,
                onEnter: (_) => setState(() => _overRemove = true),
                onExit: (_) => setState(() => _overRemove = false),
                child: GestureDetector(
                  key: ValueKey('remove-tag-${widget.tag.label}'),
                  onTap: widget.onRemove,
                  child: Container(
                    width: 18,
                    height: 18,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _overRemove ? Colors.white.withValues(alpha: .22) : Colors.transparent,
                    ),
                    child: const Icon(Icons.close_rounded, size: 14, color: Colors.white),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A tag the AI suggests: a dashed outline with ✨, the label in the tag's own
/// color and ✓ / ✗ (`.ghost-pill`).
class _GhostTagPill extends StatelessWidget {
  const _GhostTagPill({
    required this.tag,
    required this.acceptBusy,
    required this.rejectBusy,
    required this.onAccept,
    required this.onReject,
  });

  final Tag tag;
  final bool acceptBusy;
  final bool rejectBusy;
  final VoidCallback onAccept;
  final VoidCallback onReject;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return DashedBorder(
      color: p.border,
      width: 1.5,
      dash: 4.5,
      gap: 3,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(8, 2, 4, 2),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Tooltip(
              message: 'AI suggestion',
              child: Icon(Icons.auto_awesome_rounded, size: 13, color: p.accent),
            ),
            const SizedBox(width: 3),
            Text(tag.label, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: tag.color)),
            const SizedBox(width: 4), // 3 px gap + 1 px margin of `.ghost-pill-acts`
            GhostAction.accept(busy: acceptBusy, onTap: onAccept),
            GhostAction.reject(busy: rejectBusy, onTap: onReject),
          ],
        ),
      ),
    );
  }
}

/// The dashed "+ Add tag" button and the dropdown it opens.
class _AddTag extends StatelessWidget {
  const _AddTag({required this.library, required this.used, required this.onAdd});

  final List<Tag> library;
  final List<Tag> used;
  final ValueChanged<String> onAdd;

  @override
  Widget build(BuildContext context) {
    return Popover(
      gap: 5,
      minWidth: 200,
      targetBuilder: (context, open, toggle) => _AddTagButton(onTap: toggle),
      panelBuilder: (context, close) => _TagDropdown(
        library: library,
        used: used,
        onAdd: (label) {
          onAdd(label);
          close();
        },
        onClose: close,
      ),
    );
  }
}

class _AddTagButton extends StatefulWidget {
  const _AddTagButton({required this.onTap});

  final VoidCallback onTap;

  @override
  State<_AddTagButton> createState() => _AddTagButtonState();
}

class _AddTagButtonState extends State<_AddTagButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final color = _hover ? p.accent : p.text3;
    const padding = EdgeInsets.symmetric(horizontal: 10, vertical: 2);
    final label = Text('+ Add tag', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: color));
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: _hover
            // Hovered: solid accent border on a tinted fill.
            ? Container(
                padding: padding,
                decoration: BoxDecoration(
                  color: p.accentLight,
                  borderRadius: BorderRadius.circular(100),
                  border: Border.all(color: p.accent),
                ),
                child: label,
              )
            : DashedBorder(
                color: p.border,
                child: Container(padding: padding, child: label),
              ),
      ),
    );
  }
}

/// Search box, the tags not yet on this transcription, and a "Create" row for
/// a name that is not in the library (`.tag-dd`).
class _TagDropdown extends StatefulWidget {
  const _TagDropdown({required this.library, required this.used, required this.onAdd, required this.onClose});

  final List<Tag> library;
  final List<Tag> used;
  final ValueChanged<String> onAdd;
  final VoidCallback onClose;

  @override
  State<_TagDropdown> createState() => _TagDropdownState();
}

class _TagDropdownState extends State<_TagDropdown> {
  final _input = TextEditingController();

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  String get _query => _input.text.trim().toLowerCase();

  bool get _isUsed => widget.used.any((t) => t.label == _query);

  Tag? get _exactAvailable {
    for (final t in widget.library) {
      if (t.label == _query && !widget.used.contains(t)) return t;
    }
    return null;
  }

  /// Enter: add the tag with exactly this name, or create it. A name already
  /// on this transcription does nothing (the design would create a duplicate).
  void _submit() {
    if (_query.isEmpty || _isUsed) return;
    widget.onAdd(_exactAvailable?.label ?? _query);
  }

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final query = _query;
    final available = [
      for (final t in widget.library)
        if (!widget.used.contains(t) && (query.isEmpty || t.label.contains(query))) t,
    ];
    final inLibrary = widget.library.any((t) => t.label == query);
    final showCreate = query.isNotEmpty && !inLibrary && !_isUsed;

    return PopoverPanel(
      key: const ValueKey('tag-dropdown'),
      padding: EdgeInsets.zero,
      child: CallbackShortcuts(
        bindings: {const SingleActivator(LogicalKeyboardKey.escape): widget.onClose},
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              padding: const EdgeInsets.fromLTRB(8, 8, 8, 4),
              decoration: BoxDecoration(border: Border(bottom: BorderSide(color: p.border))),
              child: _SearchField(controller: _input, onChanged: () => setState(() {}), onSubmitted: _submit),
            ),
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 180),
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (final tag in available)
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 6),
                        child: InkWell(
                          onTap: () => widget.onAdd(tag.label),
                          borderRadius: BorderRadius.circular(7),
                          hoverColor: p.accentLight,
                          child: Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 5),
                            child: Align(alignment: Alignment.centerLeft, child: MiniTag(tag)),
                          ),
                        ),
                      ),
                    if (available.isEmpty && query.isEmpty)
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                        child: Text(
                          'All tags already added',
                          textAlign: TextAlign.center,
                          style: TextStyle(fontSize: 12, fontStyle: FontStyle.italic, color: p.text3),
                        ),
                      ),
                    if (showCreate)
                      Container(
                        margin: const EdgeInsets.only(top: 2),
                        decoration: BoxDecoration(border: Border(top: BorderSide(color: p.border))),
                        child: InkWell(
                          onTap: () => widget.onAdd(query),
                          hoverColor: p.accentLight,
                          child: Padding(
                            padding: const EdgeInsets.fromLTRB(12, 5, 6, 5),
                            child: Row(
                              children: [
                                Icon(Icons.add_rounded, size: 14, color: p.accent),
                                const SizedBox(width: 6),
                                Flexible(
                                  child: Text(
                                    'Create "$query"',
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(fontSize: 12.5, color: p.accent),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SearchField extends StatelessWidget {
  const _SearchField({required this.controller, required this.onChanged, required this.onSubmitted});

  final TextEditingController controller;
  final VoidCallback onChanged;
  final VoidCallback onSubmitted;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    OutlineInputBorder border(Color color) => OutlineInputBorder(
          borderRadius: BorderRadius.circular(7),
          borderSide: BorderSide(color: color),
        );
    return TextField(
      controller: controller,
      autofocus: true,
      onChanged: (_) => onChanged(),
      onSubmitted: (_) => onSubmitted(),
      cursorColor: p.accent,
      style: TextStyle(fontSize: 12.5, color: p.text),
      decoration: InputDecoration(
        isDense: true,
        filled: true,
        fillColor: p.background,
        focusColor: p.surface, // fill while focused
        hintText: 'Search or create tag…',
        hintStyle: TextStyle(fontSize: 12.5, color: p.text3),
        contentPadding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
        border: border(p.border),
        enabledBorder: border(p.border),
        focusedBorder: border(p.accent),
      ),
    );
  }
}
