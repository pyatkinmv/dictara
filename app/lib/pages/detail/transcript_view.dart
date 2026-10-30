import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/material.dart';
import 'package:scrollable_positioned_list/scrollable_positioned_list.dart';

import '../../models/speaker.dart';
import '../../models/transcription_detail.dart';
import '../../shell/breakpoints.dart';
import '../../state/audio_player_controller.dart';
import '../../theme/app_palette.dart';
import '../../util/format.dart';
import '../../widgets/popover.dart';
import 'audio_player_bar.dart';
import 'phrase_text.dart';
import 'speaker_picker.dart';

/// Where playback is, as the phrases need it: which one is being spoken, and whether a phrase can be played from.
@immutable
class _Playback {
  const _Playback({this.active, this.canPlay = false});

  /// Index of the phrase being spoken (the last one that has started); null when nothing is playing yet.
  final int? active;
  final bool canPlay;

  @override
  bool operator ==(Object other) => other is _Playback && other.active == active && other.canPlay == canPlay;

  @override
  int get hashCode => Object.hash(active, canPlay);
}

/// The Transcript tab: segments grouped into blocks by speaker, each with a
/// colored left border (`.transcript-pane`). Clicking a block's speaker name
/// opens the Speaker Picker. While the audio plays, the phrase being spoken is marked, what was said is dark and
/// what is ahead is faded, and the list follows the playback (until the user scrolls it themselves).
class TranscriptView extends StatefulWidget {
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
  State<TranscriptView> createState() => _TranscriptViewState();
}

class _TranscriptViewState extends State<TranscriptView> {
  final ItemScrollController _scroll = ItemScrollController();
  final ItemPositionsListener _positions = ItemPositionsListener.create();
  final ValueNotifier<_Playback> _playback = ValueNotifier(const _Playback());

  /// Whether the list follows the phrase being spoken. The user scrolling by hand turns it off; playing again
  /// or jumping in the audio turns it back on.
  bool _following = true;

  /// How many scrolls of the list's own are running; any other movement of the list is the user's.
  int _selfScrolls = 0;
  bool _wasPlaying = false;
  Duration _lastPosition = Duration.zero;

  /// A jump this long between two position updates is a seek, not playback (updates come about four a second).
  static const _seekJump = Duration(milliseconds: 1500);

  @override
  void initState() {
    super.initState();
    widget.player.addListener(_onPlayer);
    _onPlayer();
  }

  @override
  void didUpdateWidget(TranscriptView old) {
    super.didUpdateWidget(old);
    if (old.player != widget.player) {
      old.player.removeListener(_onPlayer);
      widget.player.addListener(_onPlayer);
    }
    _onPlayer();
  }

  @override
  void dispose() {
    widget.player.removeListener(_onPlayer);
    _playback.dispose();
    super.dispose();
  }

  void _onPlayer() {
    final player = widget.player;
    final position = player.position;
    // Before anything has played, no phrase is "current": the transcript looks as it always did.
    final started = player.playing || position > Duration.zero;
    final active = started ? widget.detail.phraseAt(position) : null;
    if (player.playing && !_wasPlaying) _following = true;
    if ((position - _lastPosition).abs() > _seekJump) _following = true;
    _wasPlaying = player.playing;
    _lastPosition = position;

    final next = _Playback(active: active, canPlay: player.hasSource && !player.unavailable && player.error == null);
    if (next == _playback.value) return;
    final changed = next.active != _playback.value.active;
    _playback.value = next;
    if (changed && active != null && _following) _scrollTo(active);
  }

  /// Brings the run holding phrase [index] into view when it is not comfortably visible already.
  void _scrollTo(int index) {
    final runs = widget.detail.runs;
    final run = runs.indexWhere((r) => index >= r.firstIndex && index < r.firstIndex + r.segments.length);
    if (run < 0 || !_scroll.isAttached) return;
    for (final position in _positions.itemPositions.value) {
      // Well inside the window: leave it where it is, a list that moves at every phrase is tiring.
      if (position.index == run && position.itemLeadingEdge >= 0 && position.itemTrailingEdge <= 0.75) return;
    }
    _selfScrolls++;
    _scroll
        .scrollTo(index: run, alignment: 0.2, duration: const Duration(milliseconds: 300), curve: Curves.easeInOut)
        .whenComplete(() => _selfScrolls--);
  }

  @override
  Widget build(BuildContext context) {
    final runs = widget.detail.runs;
    // Selectable, so a passage can be copied.
    return Stack(
      children: [
        // The list moving when it is not the list's own scroll (wheel, trackpad, drag, scroll bar) is the user's.
        NotificationListener<ScrollUpdateNotification>(
          onNotification: (n) {
            if (_selfScrolls == 0 && n.scrollDelta != 0) _following = false;
            return false;
          },
          child: SelectionArea(
            child: ScrollablePositionedList.separated(
              itemScrollController: _scroll,
              itemPositionsListener: _positions,
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
              itemCount: runs.length,
              separatorBuilder: (_, _) => const SizedBox(height: 5),
              itemBuilder: (context, i) => _RunBlock(
                detail: widget.detail,
                run: runs[i],
                library: widget.library,
                onAssign: widget.onAssign,
                playback: _playback,
                onPlayFrom: (start) {
                  _following = true;
                  widget.player.seekAndPlay(start);
                },
              ),
            ),
          ),
        ),
        Positioned(left: 0, right: 0, bottom: 16, child: Center(child: AudioPlayerBar(controller: widget.player))),
      ],
    );
  }
}

class _RunBlock extends StatefulWidget {
  const _RunBlock({
    required this.detail,
    required this.run,
    required this.library,
    required this.onAssign,
    required this.playback,
    required this.onPlayFrom,
  });

  final TranscriptionDetail detail;
  final SpeakerRun run;
  final List<Speaker> library;
  final void Function(Set<int> segmentIndexes, String name, bool allBySpeaker) onAssign;
  final ValueListenable<_Playback> playback;

  /// Plays from the start of a phrase (its play button).
  final void Function(Duration start) onPlayFrom;

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
                playback: widget.playback,
                onPlayFrom: widget.onPlayFrom,
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
    required this.playback,
    required this.onPlayFrom,
  });

  final TranscriptionDetail detail;
  final int index;
  final List<Speaker> library;
  final void Function(Set<int> segmentIndexes, String name, bool allBySpeaker) onAssign;
  final bool blockHovered;

  /// False without diarization: there is nobody to change.
  final bool canChangeSpeaker;

  final ValueListenable<_Playback> playback;
  final void Function(Duration start) onPlayFrom;

  @override
  State<_PhraseRow> createState() => _PhraseRowState();
}

class _PhraseRowState extends State<_PhraseRow> {
  bool _timeHover = false;
  bool _rowHover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final segment = widget.detail.segments[widget.index];

    Widget time(bool open, VoidCallback? toggle, {required bool current}) {
      final active = widget.canChangeSpeaker && (_timeHover || open);
      final text = Text(
        formatTimestamp(segment.start),
        style: TextStyle(fontSize: 11, color: active || current ? p.accent : p.text3),
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

    final mobile = isMobileWidth(context);
    return MouseRegion(
      onEnter: (_) => setState(() => _rowHover = true),
      onExit: (_) => setState(() => _rowHover = false),
      child: ValueListenableBuilder<_Playback>(
        valueListenable: widget.playback,
        builder: (context, playback, _) {
          final active = playback.active;
          final current = active == widget.index;
          // Once playback has started: what was said is dark, what is ahead is faded. Before that, as it always was.
          final Color textColor;
          if (active == null) {
            textColor = widget.blockHovered ? p.text : p.text2;
          } else {
            textColor = widget.index > active ? p.text3 : p.text;
          }
          final row = Container(
            // The phrase being spoken sits on a soft tint; for the others the same box is clear, so nothing shifts.
            padding: const EdgeInsets.fromLTRB(6, 2, 6, 2),
            decoration: BoxDecoration(
              color: current ? p.accent.withValues(alpha: .10) : Colors.transparent,
              borderRadius: BorderRadius.circular(6),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic,
              children: [
                // The column the play button sits in (drawn over the row below); none on a phone, which has no pointer.
                if (!mobile) const SizedBox(width: 18),
                SizedBox(
                  width: 36,
                  child: widget.canChangeSpeaker
                      ? Popover(
                          estimatedSize: const Size(280, 320),
                          useRootOverlay: true,
                          targetBuilder: (context, open, toggle) => time(open, toggle, current: current),
                          panelBuilder: (context, close) => SpeakerPicker(
                            detail: widget.detail,
                            segmentIndex: widget.index,
                            library: widget.library,
                            onPick: widget.onAssign,
                            onClose: close,
                          ),
                        )
                      : time(false, null, current: current),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: PhraseText(
                    index: widget.index,
                    text: segment.text,
                    rowHovered: _rowHover,
                    style: TextStyle(
                      fontSize: _phraseFontSize,
                      height: _phraseLineHeight,
                      color: textColor,
                      fontWeight: current ? FontWeight.w500 : FontWeight.w400,
                    ),
                  ),
                ),
              ],
            ),
          );
          if (mobile) return row;
          // The button is laid over the row, centered on the first line of the text: an icon has no text baseline,
          // so as a child of the baseline-aligned row it would sit too high.
          return Stack(
            children: [
              row,
              Positioned(
                left: 6,
                top: 2,
                width: 18,
                child: _PlayFromHere(
                  visible: _rowHover && playback.canPlay,
                  onTap: () => widget.onPlayFrom(segment.start),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

const _phraseFontSize = 13.5;
const _phraseLineHeight = 1.52;

/// The play button at the start of a phrase's row, centered on the first line of the text (an icon font has its
/// own baseline, so aligning it with the text's would sit it too high).
class _PlayFromHere extends StatelessWidget {
  const _PlayFromHere({required this.visible, required this.onTap});

  final bool visible;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return IgnorePointer(
      ignoring: !visible,
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 150),
        opacity: visible ? 1 : 0,
        child: Tooltip(
          message: 'Play from here',
          waitDuration: const Duration(milliseconds: 600),
          child: MouseRegion(
            cursor: SystemMouseCursors.click,
            child: GestureDetector(
              onTap: onTap,
              behavior: HitTestBehavior.opaque,
              child: SizedBox(
                height: _phraseFontSize * _phraseLineHeight,
                child: Center(child: Icon(Icons.play_arrow_rounded, size: 16, color: p.accent)),
              ),
            ),
          ),
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
