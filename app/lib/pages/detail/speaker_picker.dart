import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../models/speaker.dart';
import '../../models/transcription_detail.dart';
import '../../theme/app_palette.dart';
import '../../widgets/form_marks.dart';
import '../../widgets/popover.dart';

/// Which segments a chosen speaker is applied to.
enum PickerScope { segment, allBySpeaker }

/// Floating panel for reassigning a speaker block (`.spk-picker`): search the
/// speaker library, pick a speaker or create a new one, and choose whether it
/// applies to this segment only or to everything the current speaker says.
class SpeakerPicker extends StatefulWidget {
  const SpeakerPicker({
    super.key,
    required this.detail,
    required this.run,
    required this.library,
    required this.onPick,
    required this.onClose,
  });

  final TranscriptionDetail detail;

  /// The block whose header was clicked.
  final SpeakerRun run;
  final List<Speaker> library;

  /// Called with the segments to reassign and the chosen (or new) speaker name.
  final void Function(Set<int> segmentIndexes, String name) onPick;
  final VoidCallback onClose;

  @override
  State<SpeakerPicker> createState() => _SpeakerPickerState();
}

class _SpeakerPickerState extends State<SpeakerPicker> {
  final _input = TextEditingController();
  PickerScope _scope = PickerScope.segment;

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  String get _typed => _input.text.trim();

  Speaker? get _exact {
    final q = _typed.toLowerCase();
    for (final s in widget.library) {
      if (s.name.toLowerCase() == q) return s;
    }
    return null;
  }

  /// "This segment only" changes the first segment of the block, as in the
  /// design; "All segments by X" every segment now attributed to X.
  void _pick(String name) {
    final segments = switch (_scope) {
      PickerScope.segment => {widget.run.firstIndex},
      PickerScope.allBySpeaker => widget.detail.segmentsBySpeaker(widget.run.speaker!.name),
    };
    widget.onPick(segments, name);
    widget.onClose();
  }

  /// Enter: the speaker with exactly this name, or a new one.
  void _submit() {
    if (_typed.isEmpty) return;
    _pick(_exact?.name ?? _typed);
  }

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final q = _typed.toLowerCase();
    final matches = [
      for (final s in widget.library)
        if (q.isEmpty || s.name.toLowerCase().contains(q)) s,
    ];
    final showCreate = q.isNotEmpty && _exact == null;

    return SizedBox(
      width: 280,
      child: PopoverPanel(
        key: const ValueKey('speaker-picker'),
        padding: EdgeInsets.zero,
        radius: 14,
        shadow: const [BoxShadow(color: Color(0x24000000), blurRadius: 32, offset: Offset(0, 8))],
        child: CallbackShortcuts(
          bindings: {const SingleActivator(LogicalKeyboardKey.escape): widget.onClose},
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _SearchRow(controller: _input, onChanged: () => setState(() {}), onSubmitted: _submit),
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 180),
                child: SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Padding(
                        padding: const EdgeInsets.all(6),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            for (final s in matches) _PickItem(speaker: s, onTap: () => _pick(s.name)),
                            if (matches.isEmpty)
                              Padding(
                                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 12),
                                child: Text(
                                  q.isEmpty ? 'No speakers yet' : 'No match',
                                  textAlign: TextAlign.center,
                                  style: TextStyle(fontSize: 12, fontStyle: FontStyle.italic, color: p.text3),
                                ),
                              ),
                          ],
                        ),
                      ),
                      if (showCreate) _CreateRow(name: _typed, onTap: () => _pick(_typed)),
                    ],
                  ),
                ),
              ),
              _ScopeChooser(
                scope: _scope,
                currentName: widget.run.speaker!.name,
                onChanged: (scope) => setState(() => _scope = scope),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SearchRow extends StatelessWidget {
  const _SearchRow({required this.controller, required this.onChanged, required this.onSubmitted});

  final TextEditingController controller;
  final VoidCallback onChanged;
  final VoidCallback onSubmitted;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(border: Border(bottom: BorderSide(color: p.border))),
      child: Row(
        children: [
          Icon(Icons.search_rounded, size: 16, color: p.text3),
          const SizedBox(width: 7),
          Expanded(
            child: TextField(
              controller: controller,
              autofocus: true,
              onChanged: (_) => onChanged(),
              onSubmitted: (_) => onSubmitted(),
              cursorColor: p.accent,
              style: TextStyle(fontSize: 13, color: p.text),
              decoration: InputDecoration(
                isDense: true,
                border: InputBorder.none,
                contentPadding: EdgeInsets.zero,
                hintText: 'Search speakers…',
                hintStyle: TextStyle(fontSize: 13, color: p.text3),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// A speaker of the library: a colored avatar with initials and the name (`.spk-pick-item`).
class _PickItem extends StatefulWidget {
  const _PickItem({required this.speaker, required this.onTap});

  final Speaker speaker;
  final VoidCallback onTap;

  @override
  State<_PickItem> createState() => _PickItemState();
}

class _PickItemState extends State<_PickItem> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: InkWell(
        onTap: widget.onTap,
        borderRadius: BorderRadius.circular(9),
        hoverColor: p.accentLight,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 7),
          child: Row(
            children: [
              Container(
                width: 28,
                height: 28,
                alignment: Alignment.center,
                decoration: BoxDecoration(color: widget.speaker.color, shape: BoxShape.circle),
                child: Text(
                  widget.speaker.initials,
                  style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Colors.white),
                ),
              ),
              const SizedBox(width: 9),
              Flexible(
                child: Text(
                  widget.speaker.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 13, color: _hover ? p.text : p.text2),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// `+ Create "name"`, edge to edge under the list (`.spk-pick-create`).
class _CreateRow extends StatelessWidget {
  const _CreateRow({required this.name, required this.onTap});

  final String name;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return DecoratedBox(
      decoration: BoxDecoration(border: Border(top: BorderSide(color: p.border))),
      child: InkWell(
        onTap: onTap,
        hoverColor: p.accentLight,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 7, 8, 7),
          child: Row(
            children: [
              Icon(Icons.add_rounded, size: 15, color: p.accent),
              const SizedBox(width: 8),
              Flexible(
                child: Text(
                  'Create "$name"',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 13, color: p.accent),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// "Apply to" radios on a tinted strip at the bottom (`.spk-picker-scope`).
class _ScopeChooser extends StatelessWidget {
  const _ScopeChooser({required this.scope, required this.currentName, required this.onChanged});

  final PickerScope scope;
  final String currentName;
  final ValueChanged<PickerScope> onChanged;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: p.background,
        border: Border(top: BorderSide(color: p.border)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            'APPLY TO',
            style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, letterSpacing: .7, color: p.text3),
          ),
          const SizedBox(height: 6),
          _ScopeOption(
            selected: scope == PickerScope.segment,
            onTap: () => onChanged(PickerScope.segment),
            child: const Text('This segment only'),
          ),
          const SizedBox(height: 2),
          _ScopeOption(
            selected: scope == PickerScope.allBySpeaker,
            onTap: () => onChanged(PickerScope.allBySpeaker),
            child: Text.rich(
              TextSpan(
                children: [
                  const TextSpan(text: 'All segments by '),
                  TextSpan(text: currentName, style: const TextStyle(fontWeight: FontWeight.w700)),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ScopeOption extends StatelessWidget {
  const _ScopeOption({required this.selected, required this.onTap, required this.child});

  final bool selected;
  final VoidCallback onTap;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 3),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: RadioMark(selected: selected, size: 13),
              ),
              const SizedBox(width: 7),
              Expanded(
                child: DefaultTextStyle.merge(
                  style: TextStyle(fontSize: 12, height: 1.4, color: p.text2),
                  child: child,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
