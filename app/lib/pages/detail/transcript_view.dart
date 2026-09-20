import 'package:flutter/material.dart';

import '../../models/transcription_detail.dart';
import '../../theme/app_palette.dart';
import '../../util/format.dart';

/// The Transcript tab: segments grouped into blocks by speaker, each with a
/// colored left border (`.transcript-pane`).
class TranscriptView extends StatelessWidget {
  const TranscriptView({super.key, required this.detail});

  final TranscriptionDetail detail;

  @override
  Widget build(BuildContext context) {
    final runs = detail.runs;
    // Selectable, so a passage can be copied.
    return SelectionArea(
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
        itemCount: runs.length,
        separatorBuilder: (_, _) => const SizedBox(height: 5),
        itemBuilder: (context, i) => _RunBlock(run: runs[i]),
      ),
    );
  }
}

class _RunBlock extends StatefulWidget {
  const _RunBlock({required this.run});

  final SpeakerRun run;

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
            // Clicking the name opens the Speaker Picker (iteration 6).
            Padding(
              padding: const EdgeInsets.fromLTRB(0, 3, 6, 3),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(width: 6, height: 6, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
                  const SizedBox(width: 6),
                  Text(
                    run.speaker.name.toUpperCase(),
                    style: TextStyle(
                      fontSize: 10.5,
                      fontWeight: FontWeight.w800,
                      letterSpacing: .07 * 10.5,
                      color: color,
                    ),
                  ),
                ],
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
