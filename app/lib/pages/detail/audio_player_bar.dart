import 'package:flutter/material.dart';

import '../../state/audio_player_controller.dart';
import '../../theme/app_palette.dart';
import '../../util/format.dart';

/// The floating pill player over the transcript (design variant C): play/pause, position, a seek slider and
/// the total duration. Only shown on the Transcript tab — `TranscriptView` positions it.
class AudioPlayerBar extends StatelessWidget {
  const AudioPlayerBar({super.key, required this.controller});

  final AudioPlayerController controller;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        if (!controller.hasSource) return const SizedBox.shrink();
        final duration = controller.duration;
        final position = controller.position;
        final maxMs = duration.inMilliseconds > 0 ? duration.inMilliseconds.toDouble() : 1.0;
        final valueMs = position.inMilliseconds.clamp(0, maxMs.round()).toDouble();
        final seekable = duration > Duration.zero;
        return Container(
          constraints: const BoxConstraints(maxWidth: 460),
          padding: const EdgeInsets.fromLTRB(7, 7, 16, 7),
          decoration: BoxDecoration(
            color: p.surface,
            border: Border.all(color: p.border),
            borderRadius: BorderRadius.circular(100),
            boxShadow: [BoxShadow(color: p.accent.withValues(alpha: .16), blurRadius: 20, offset: const Offset(0, 4))],
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              _PlayButton(controller: controller),
              const SizedBox(width: 8),
              Text(formatTimestamp(position), style: TextStyle(fontSize: 11, color: p.text3)),
              const SizedBox(width: 8),
              SizedBox(
                width: 220,
                child: SliderTheme(
                  data: SliderThemeData(
                    trackHeight: 3,
                    thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
                    overlayShape: SliderComponentShape.noOverlay,
                    activeTrackColor: p.accent,
                    inactiveTrackColor: p.border,
                    thumbColor: p.accent,
                  ),
                  child: Slider(
                    value: valueMs,
                    min: 0,
                    max: maxMs,
                    onChanged: seekable ? (v) => controller.seek(Duration(milliseconds: v.round())) : null,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Text(formatTimestamp(duration), style: TextStyle(fontSize: 11, color: p.text3)),
            ],
          ),
        );
      },
    );
  }
}

class _PlayButton extends StatelessWidget {
  const _PlayButton({required this.controller});

  final AudioPlayerController controller;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return SizedBox(
      width: 32,
      height: 32,
      child: Material(
        color: p.accent,
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: controller.togglePlay,
          child: Icon(
            controller.playing ? Icons.pause_rounded : Icons.play_arrow_rounded,
            size: 17,
            color: Colors.white,
          ),
        ),
      ),
    );
  }
}
