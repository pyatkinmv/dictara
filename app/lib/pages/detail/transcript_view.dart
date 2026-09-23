import 'package:flutter/material.dart';

import '../../models/speaker.dart';
import '../../models/transcription_detail.dart';
import '../../state/audio_player_controller.dart';
import '../../theme/app_palette.dart';
import '../../util/format.dart';
import '../../widgets/popover.dart';
import 'audio_player_bar.dart';
import 'speaker_picker.dart';

/// The Transcript tab: segments grouped into blocks by speaker, each with a
/// colored left border (`.transcript-pane`). Clicking a block's speaker name
/// opens the Speaker Picker.
class TranscriptView extends StatelessWidget {
  const TranscriptView({super.key, required this.detail, required this.library, required this.onAssign, required this.player});

  final TranscriptionDetail detail;

  /// The speaker library offered by the picker.
  final List<Speaker> library;

  /// Reassigns segments to the speaker with this name (created if new).
  final void Function(Set<int> segmentIndexes, String name, bool allBySpeaker) onAssign;

  /// The audio player, floating over the transcript (only on this tab). Its own bar stays invisible until
  /// `player.hasSource` — the URL fetch that feeds it is a separate round trip from the transcript itself.
  final AudioPlayerController player;

  @override
  Widget build(BuildContext context) {
    final runs = detail.runs;
    // Selectable, so a passage can be copied.
    return Stack(
      children: [
        SelectionArea(
          child: ListView.separated(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
            itemCount: runs.length,
            separatorBuilder: (_, _) => const SizedBox(height: 5),
            itemBuilder: (context, i) => _RunBlock(detail: detail, run: runs[i], library: library, onAssign: onAssign),
          ),
        ),
        Positioned(left: 0, right: 0, bottom: 16, child: Center(child: AudioPlayerBar(controller: player))),
      ],
    );
  }
}

class _RunBlock extends StatefulWidget {
  const _RunBlock({required this.detail, required this.run, required this.library, required this.onAssign});

  final TranscriptionDetail detail;
  final SpeakerRun run;
  final List<Speaker> library;
  final void Function(Set<int> segmentIndexes, String name, bool allBySpeaker) onAssign;

  @override
  State<_RunBlock> createState() => _RunBlockState();
}

class _RunBlockState extends State<_RunBlock> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final run = widget.run;
    final speaker = run.speaker;
    // Without diarization there is nobody to name: a neutral rule and no header.
    final color = speaker?.color ?? p.border;
    return MouseRegion(
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 6, 0, 6),
        decoration: BoxDecoration(border: Border(left: BorderSide(color: color, width: 3))),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (speaker != null) ...[
              Popover(
                // The picker is tall; flip it above the header near the bottom of
                // the window, and draw it over everything like the design does.
                estimatedSize: const Size(280, 320),
                useRootOverlay: true,
                targetBuilder: (context, open, toggle) => _RunHeader(speaker: speaker, onTap: toggle),
                panelBuilder: (context, close) => SpeakerPicker(
                  detail: widget.detail,
                  segmentIndex: run.firstIndex,
                  library: widget.library,
                  onPick: widget.onAssign,
                  onClose: close,
                ),
              ),
              const SizedBox(height: 4),
            ],
            for (var i = 0; i < run.segments.length; i++) ...[
              if (i > 0) const SizedBox(height: 2),
              _PhraseRow(
                detail: widget.detail,
                index: run.firstIndex + i, // a run is a stretch of consecutive segments
                library: widget.library,
                onAssign: widget.onAssign,
                blockHovered: _hover,
                canChangeSpeaker: speaker != null,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// One line of a block: timestamp and text. Clicking the timestamp changes the speaker of this phrase alone:
/// phrases of one speaker are shown as one block, and only the first of them has a header to click.
class _PhraseRow extends StatefulWidget {
  const _PhraseRow({
    required this.detail,
    required this.index,
    required this.library,
    required this.onAssign,
    required this.blockHovered,
    required this.canChangeSpeaker,
  });

  final TranscriptionDetail detail;
  final int index;
  final List<Speaker> library;
  final void Function(Set<int> segmentIndexes, String name, bool allBySpeaker) onAssign;
  final bool blockHovered;

  /// False without diarization: there is nobody to change.
  final bool canChangeSpeaker;

  @override
  State<_PhraseRow> createState() => _PhraseRowState();
}

class _PhraseRowState extends State<_PhraseRow> {
  bool _timeHover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final segment = widget.detail.segments[widget.index];

    Widget time(bool open, VoidCallback? toggle) {
      final active = widget.canChangeSpeaker && (_timeHover || open);
      final text = Text(
        formatTimestamp(segment.start),
        style: TextStyle(fontSize: 11, color: active ? p.accent : p.text3),
      );
      if (!widget.canChangeSpeaker) return text;
      return Tooltip(
        message: 'Change speaker of this phrase',
        waitDuration: const Duration(milliseconds: 600),
        child: MouseRegion(
          onEnter: (_) => setState(() => _timeHover = true),
          onExit: (_) => setState(() => _timeHover = false),
          child: GestureDetector(
            onTap: toggle,
            behavior: HitTestBehavior.opaque,
            // Scales down instead of overflowing when the time is long (1:02:03) and the pencil does not fit.
            child: FittedBox(
              fit: BoxFit.scaleDown,
              alignment: Alignment.centerLeft,
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // Text in a selectable area asks for the text cursor on its own, which beats the region above.
                  DefaultSelectionStyle(mouseCursor: SystemMouseCursors.click, child: text),
                  const SizedBox(width: 3),
                  // A pencil on hover, like the one next to a speaker's name in a block header.
                  AnimatedOpacity(
                    duration: const Duration(milliseconds: 150),
                    opacity: active ? 1 : 0,
                    child: Icon(Icons.edit_rounded, size: 11, color: p.text3),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    }

    return Row(
      crossAxisAlignment: CrossAxisAlignment.baseline,
      textBaseline: TextBaseline.alphabetic,
      children: [
        SizedBox(
          width: 36,
          child: widget.canChangeSpeaker
              ? Popover(
                  estimatedSize: const Size(280, 320),
                  useRootOverlay: true,
                  targetBuilder: (context, open, toggle) => time(open, toggle),
                  panelBuilder: (context, close) => SpeakerPicker(
                    detail: widget.detail,
                    segmentIndex: widget.index,
                    library: widget.library,
                    onPick: widget.onAssign,
                    onClose: close,
                  ),
                )
              : time(false, null),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            segment.text,
            style: TextStyle(fontSize: 13.5, height: 1.52, color: widget.blockHovered ? p.text : p.text2),
          ),
        ),
      ],
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
                  DefaultSelectionStyle(
                    mouseCursor: SystemMouseCursors.click,
                    child: Text(
                      widget.speaker.name.toUpperCase(),
                      style: TextStyle(
                        fontSize: 10.5,
                        fontWeight: FontWeight.w800,
                        letterSpacing: .07 * 10.5,
                        color: color,
                      ),
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
