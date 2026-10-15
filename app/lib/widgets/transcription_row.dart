import 'package:flutter/material.dart';

import '../models/transcription.dart';
import '../theme/app_palette.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import 'mini_tag.dart';
import 'status_badge.dart';

/// One row of the transcriptions list (`.row` in the design). With no [onTap] the row is only informative: no
/// hand cursor and no hover highlight, so it does not look like something that opens (a transcription that is
/// still being made, or failed, has no page to open).
class TranscriptionRow extends StatefulWidget {
  const TranscriptionRow({super.key, required this.item, this.onTap});

  final Transcription item;
  final VoidCallback? onTap;

  @override
  State<TranscriptionRow> createState() => _TranscriptionRowState();
}

class _TranscriptionRowState extends State<TranscriptionRow> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final item = widget.item;
    final interactive = widget.onTap != null;
    return MouseRegion(
      cursor: interactive ? SystemMouseCursors.click : SystemMouseCursors.basic,
      onEnter: (_) => interactive ? setState(() => _hover = true) : null,
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 11),
          decoration: BoxDecoration(
            color: p.surface,
            borderRadius: BorderRadius.circular(appRadius),
            border: Border.all(color: p.border),
            boxShadow: [
              if (_hover)
                BoxShadow(color: p.accent.withValues(alpha: .12), blurRadius: 10, offset: const Offset(0, 2)),
            ],
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: _StatusIcon(status: item.status),
              ),
              const SizedBox(width: 11),
              Expanded(child: _Info(item: item)),
              const SizedBox(width: 11),
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: _Trailing(item: item, rowHovered: _hover),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _StatusIcon extends StatelessWidget {
  const _StatusIcon({required this.status});

  final TranscriptionStatus status;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      width: 34,
      height: 34,
      decoration: BoxDecoration(color: status.background(p), borderRadius: BorderRadius.circular(9)),
      child: Icon(status.icon, size: 17, color: status.foreground(p)),
    );
  }
}

class _Info extends StatelessWidget {
  const _Info({required this.item});

  final Transcription item;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          item.title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 2),
        Row(
          children: [
            if (item.duration != null) ...[
              _Meta(icon: Icons.schedule_rounded, text: formatDuration(item.duration!)),
              const SizedBox(width: 10),
            ],
            _Meta(icon: Icons.calendar_today_rounded, text: formatDay(item.uploadedAt)),
          ],
        ),
        const SizedBox(height: 5),
        Wrap(
          spacing: 4,
          runSpacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            for (final tag in item.tags) MiniTag(tag),
            if (item.speakerCount > 0) _SpeakerPill(count: item.speakerCount),
          ],
        ),
        if (item.status == TranscriptionStatus.processing) ...[
          const SizedBox(height: 5),
          _Progress(pct: _barPercent(item)),
          const SizedBox(height: 3),
          Text(
            _progressLabel(item),
            style: TextStyle(fontSize: 11, height: 14 / 11, color: p.warn),
          ),
        ],
      ],
    );
  }
}

class _Meta extends StatelessWidget {
  const _Meta({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 13, color: p.text3),
        const SizedBox(width: 3),
        Text(text, style: TextStyle(fontSize: 12, color: p.text2)),
      ],
    );
  }
}

class _SpeakerPill extends StatelessWidget {
  const _SpeakerPill({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      constraints: const BoxConstraints(minHeight: 20),
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(100),
        border: Border.all(color: p.border),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.record_voice_over_rounded, size: 13, color: p.text3),
          const SizedBox(width: 3),
          Text('$count', style: TextStyle(fontSize: 11, color: p.text3)),
        ],
      ),
    );
  }
}

/// Progress bar whose fill pulses (`.prog-fill`, 1.5 s ease-in-out loop).
class _Progress extends StatefulWidget {
  const _Progress({required this.pct});

  final int pct;

  @override
  State<_Progress> createState() => _ProgressState();
}

class _ProgressState extends State<_Progress> with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 750),
  )..repeat(reverse: true);

  late final Animation<double> _opacity = Tween(begin: 1.0, end: .4).animate(
    CurvedAnimation(parent: _controller, curve: Curves.easeInOut),
  );

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return ClipRRect(
      borderRadius: BorderRadius.circular(2),
      child: SizedBox(
        height: 3,
        child: ColoredBox(
          color: p.border,
          child: Align(
            alignment: Alignment.centerLeft,
            child: FractionallySizedBox(
              widthFactor: (widget.pct / 100).clamp(0.0, 1.0),
              child: FadeTransition(
                opacity: _opacity,
                child: SizedBox.expand(child: ColoredBox(color: p.warn)),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _Trailing extends StatelessWidget {
  const _Trailing({required this.item, required this.rowHovered});

  final Transcription item;
  final bool rowHovered;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        StatusBadge(item.status),
        if (item.status == TranscriptionStatus.done) ...[
          const SizedBox(width: 5),
          AnimatedOpacity(
            duration: const Duration(milliseconds: 150),
            opacity: rowHovered ? 1 : 0,
            // Download arrives together with the real API; for now it only absorbs the tap.
            child: const _DownloadButton(),
          ),
        ],
        const SizedBox(width: 5),
        Icon(Icons.chevron_right_rounded, size: 20, color: p.text3),
      ],
    );
  }
}

class _DownloadButton extends StatefulWidget {
  const _DownloadButton();

  @override
  State<_DownloadButton> createState() => _DownloadButtonState();
}

class _DownloadButtonState extends State<_DownloadButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: () {},
        child: Container(
          width: 26,
          height: 26,
          decoration: BoxDecoration(
            color: _hover ? p.background : Colors.transparent,
            borderRadius: BorderRadius.circular(7),
          ),
          child: Icon(Icons.download_rounded, size: 15, color: _hover ? p.text2 : p.text3),
        ),
      ),
    );
  }
}

/// How full the bar is: the percent transcribed, and full once the later phases (which have no percentage)
/// begin. Empty while nothing is known.
int _barPercent(Transcription item) {
  final pct = item.processingPct;
  if (pct != null) return pct;
  return switch (item.processingPhase) {
    ProcessingPhase.diarizing || ProcessingPhase.summarizing => 100,
    _ => 0,
  };
}

/// What the line under the bar says: the phase, with the percent while transcribing.
String _progressLabel(Transcription item) {
  final pct = item.processingPct;
  return switch (item.processingPhase) {
    ProcessingPhase.diarizing => 'Diarizing…',
    ProcessingPhase.summarizing => 'Summarizing…',
    ProcessingPhase.transcribing => pct == null ? 'Transcribing…' : '$pct% — transcribing…',
    // The source does not name the phase; a percentage can only be transcribing.
    null => pct == null ? 'Processing…' : '$pct% — transcribing…',
  };
}
