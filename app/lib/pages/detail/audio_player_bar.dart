import 'package:flutter/material.dart';

import '../../shell/breakpoints.dart';
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
        if (controller.unavailable) return const _UnavailableNotice();
        if (!controller.hasSource) return const SizedBox.shrink();
        final duration = controller.duration;
        final position = controller.position;
        final maxMs = duration.inMilliseconds > 0 ? duration.inMilliseconds.toDouble() : 1.0;
        final valueMs = position.inMilliseconds.clamp(0, maxMs.round()).toDouble();
        final seekable = duration > Duration.zero;
        return Container(
          constraints: const BoxConstraints(maxWidth: 640),
          padding: const EdgeInsets.fromLTRB(7, 7, 16, 7),
          decoration: BoxDecoration(
            color: p.surface,
            border: Border.all(color: p.border),
            borderRadius: BorderRadius.circular(100),
            boxShadow: [BoxShadow(color: p.accent.withValues(alpha: .16), blurRadius: 20, offset: const Offset(0, 4))],
          ),
          child: Row(
            children: [
              _PlayButton(controller: controller),
              if (controller.error != null) ...[
                const SizedBox(width: 6),
                _ErrorBadge(message: controller.error!),
              ],
              const SizedBox(width: 8),
              Text(formatTimestamp(position), style: TextStyle(fontSize: 11, color: p.text3)),
              const SizedBox(width: 8),
              Expanded(
                child: SliderTheme(
                  data: _sliderTheme(p),
                  child: Slider(
                    key: const Key('audio-seek'),
                    value: valueMs,
                    min: 0,
                    max: maxMs,
                    onChanged: seekable ? (v) => controller.seek(Duration(milliseconds: v.round())) : null,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Text(formatTimestamp(duration), style: TextStyle(fontSize: 11, color: p.text3)),
              const SizedBox(width: 12),
              _VolumeControl(controller: controller),
            ],
          ),
        );
      },
    );
  }
}

SliderThemeData _sliderTheme(AppPalette p) => SliderThemeData(
      trackHeight: 3,
      thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
      overlayShape: SliderComponentShape.noOverlay,
      activeTrackColor: p.accent,
      inactiveTrackColor: p.border,
      thumbColor: p.accent,
    );

/// The speaker (click = mute / unmute) and a short slider for the level, always visible: nothing to hover for,
/// so it works the same on a touch screen.
class _VolumeControl extends StatelessWidget {
  const _VolumeControl({required this.controller});

  final AudioPlayerController controller;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final volume = controller.volume;
    final icon = volume == 0
        ? Icons.volume_off_rounded
        : volume < 0.5
            ? Icons.volume_down_rounded
            : Icons.volume_up_rounded;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Tooltip(
          message: controller.muted ? 'Unmute' : 'Mute',
          child: InkWell(
            key: const Key('audio-mute'),
            customBorder: const CircleBorder(),
            onTap: controller.toggleMute,
            child: Padding(padding: const EdgeInsets.all(4), child: Icon(icon, size: 18, color: p.text2)),
          ),
        ),
        // On a phone the level is the device's own volume; the slider would only take the seek bar's room.
        if (!isMobileWidth(context))
          SizedBox(
            width: 72,
            child: SliderTheme(
              data: _sliderTheme(p),
              child: Slider(key: const Key('audio-volume'), value: volume, onChanged: controller.setVolume),
            ),
          ),
      ],
    );
  }
}

/// Stands where the player would be when the recording's file no longer exists (nothing to play, nothing to retry).
class _UnavailableNotice extends StatelessWidget {
  const _UnavailableNotice();

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      key: const Key('audio-unavailable'),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      decoration: BoxDecoration(
        color: p.surface,
        border: Border.all(color: p.border),
        borderRadius: BorderRadius.circular(100),
        boxShadow: [BoxShadow(color: p.accent.withValues(alpha: .10), blurRadius: 20, offset: const Offset(0, 4))],
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.volume_off_rounded, size: 16, color: p.text3),
          const SizedBox(width: 8),
          Text('Audio is no longer available', style: TextStyle(fontSize: 12, color: p.text2)),
        ],
      ),
    );
  }
}

/// A small warning glyph for when playback has failed (`AudioPlayerController.error`) — the play button alone
/// reverting to its paused state gives no clue why, so this makes the failure visible without permanently
/// taking over the pill's layout the way a full error line would.
class _ErrorBadge extends StatelessWidget {
  const _ErrorBadge({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Tooltip(
      message: message,
      child: Icon(Icons.error_outline_rounded, size: 16, color: p.danger),
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
