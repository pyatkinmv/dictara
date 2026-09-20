import 'package:flutter/material.dart';

import '../../models/speaker.dart';
import '../../models/transcription_detail.dart';
import '../../theme/app_palette.dart';
import '../../util/format.dart';
import '../../widgets/popover.dart';
import 'speaker_picker.dart';

/// The Transcript tab: segments grouped into blocks by speaker, each with a
/// colored left border (`.transcript-pane`). Clicking a block's speaker name
/// opens the Speaker Picker.
class TranscriptView extends StatelessWidget {
  const TranscriptView({super.key, required this.detail, required this.library, required this.onAssign});

  final TranscriptionDetail detail;

  /// The speaker library offered by the picker.
  final List<Speaker> library;

  /// Reassigns segments to the speaker with this name (created if new).
  final void Function(Set<int> segmentIndexes, String name) onAssign;

  @override
  Widget build(BuildContext context) {
    final runs = detail.runs;
    // Selectable, so a passage can be copied.
    return SelectionArea(
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
        itemCount: runs.length,
        separatorBuilder: (_, _) => const SizedBox(height: 5),
        itemBuilder: (context, i) => _RunBlock(detail: detail, run: runs[i], library: library, onAssign: onAssign),
      ),
    );
  }
}

class _RunBlock extends StatefulWidget {
  const _RunBlock({required this.detail, required this.run, required this.library, required this.onAssign});

  final TranscriptionDetail detail;
  final SpeakerRun run;
  final List<Speaker> library;
  final void Function(Set<int> segmentIndexes, String name) onAssign;

  @override
  State<_RunBlock> createState() => _RunBlockState();
}

class _RunBlockState extends State<_RunBlock> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final run = widget.run;
    final color = run.speaker.color;
    return MouseRegion(
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 6, 0, 6),
        decoration: BoxDecoration(border: Border(left: BorderSide(color: color, width: 3))),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Popover(
              // The picker is tall; flip it above the header near the bottom of
              // the window, and draw it over everything like the design does.
              estimatedSize: const Size(280, 320),
              useRootOverlay: true,
              targetBuilder: (context, open, toggle) => _RunHeader(speaker: run.speaker, onTap: toggle),
              panelBuilder: (context, close) => SpeakerPicker(
                detail: widget.detail,
                run: run,
                library: widget.library,
                onPick: widget.onAssign,
                onClose: close,
              ),
            ),
            const SizedBox(height: 4),
            for (var i = 0; i < run.segments.length; i++) ...[
              if (i > 0) const SizedBox(height: 2),
              Row(
                crossAxisAlignment: CrossAxisAlignment.baseline,
                textBaseline: TextBaseline.alphabetic,
                children: [
                  SizedBox(
                    width: 36,
                    child: Text(
                      formatTimestamp(run.segments[i].start),
                      style: TextStyle(fontSize: 11, color: p.text3),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      run.segments[i].text,
                      style: TextStyle(fontSize: 13.5, height: 1.52, color: _hover ? p.text : p.text2),
                    ),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// The speaker's name above a block; a pencil appears on hover to show that
/// it can be clicked (`.run-hdr`).
class _RunHeader extends StatefulWidget {
  const _RunHeader({required this.speaker, required this.onTap});

  final Speaker speaker;
  final VoidCallback onTap;

  @override
  State<_RunHeader> createState() => _RunHeaderState();
}

class _RunHeaderState extends State<_RunHeader> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final color = widget.speaker.color;
    return Align(
      alignment: Alignment.centerLeft,
      child: MouseRegion(
        cursor: SystemMouseCursors.click,
        onEnter: (_) => setState(() => _hover = true),
        onExit: (_) => setState(() => _hover = false),
        child: GestureDetector(
          onTap: widget.onTap,
          behavior: HitTestBehavior.opaque,
          child: Tooltip(
            message: 'Change speaker',
            waitDuration: const Duration(milliseconds: 600),
            child: Container(
              padding: const EdgeInsets.fromLTRB(0, 3, 6, 3),
              decoration: BoxDecoration(
                color: _hover ? Colors.black.withValues(alpha: .04) : Colors.transparent,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(width: 6, height: 6, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
                  const SizedBox(width: 6),
                  Text(
                    widget.speaker.name.toUpperCase(),
                    style: TextStyle(
                      fontSize: 10.5,
                      fontWeight: FontWeight.w800,
                      letterSpacing: .07 * 10.5,
                      color: color,
                    ),
                  ),
                  const SizedBox(width: 6),
                  AnimatedOpacity(
                    duration: const Duration(milliseconds: 150),
                    opacity: _hover ? 1 : 0,
                    child: Icon(Icons.edit_rounded, size: 13, color: p.text3),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
