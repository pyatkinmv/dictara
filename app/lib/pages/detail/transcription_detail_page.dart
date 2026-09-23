import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../data/transcription_repository.dart';
import '../../state/audio_player_controller.dart';
import '../../state/transcription_detail_controller.dart';
import '../../theme/app_palette.dart';
import 'detail_header.dart';
import 'overview_view.dart';
import 'transcript_view.dart';

enum DetailTab { transcript, overview }

/// A finished transcription: header, tabs and the active tab.
class TranscriptionDetailPage extends StatefulWidget {
  /// Null when the address does not hold a valid id.
  const TranscriptionDetailPage({super.key, required this.id});

  final String? id;

  @override
  State<TranscriptionDetailPage> createState() => _TranscriptionDetailPageState();
}

class _TranscriptionDetailPageState extends State<TranscriptionDetailPage> {
  DetailTab _tab = DetailTab.transcript;

  /// Page-local, not a Provider above the router like the other detail controllers: playback has no reason
  /// to continue once you leave this transcription, so it is created and disposed with this page.
  final _audioPlayer = AudioPlayerController();

  @override
  void initState() {
    super.initState();
    _open();
  }

  @override
  void didUpdateWidget(TranscriptionDetailPage old) {
    super.didUpdateWidget(old);
    if (old.id != widget.id) {
      _tab = DetailTab.transcript;
      _open();
    }
  }

  @override
  void dispose() {
    _audioPlayer.dispose();
    super.dispose();
  }

  void _open() {
    final id = widget.id;
    if (id == null) return;
    // Not during build: opening notifies listeners (the topbar shows the title).
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<TranscriptionDetailController>().open(id);
    });
    context.read<TranscriptionRepository>().audioUrl(id).then((url) {
      // A newer id may have replaced this one, or the page may be gone, by the time this answers.
      if (mounted && widget.id == id && url != null) _audioPlayer.load(url);
    });
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<TranscriptionDetailController>();
    final detail = controller.current;

    if (widget.id == null || controller.notFound) {
      return Center(
        child: Text(
          'Transcription not found',
          style: TextStyle(fontSize: 14, color: context.palette.text3),
        ),
      );
    }
    // Still loading (or showing the previous transcription): nothing to draw yet.
    if (detail == null || detail.item.id != widget.id) return const SizedBox.shrink();

    return Column(
      children: [
        DetailHeader(detail: detail),
        _Tabs(active: _tab, onChanged: (tab) => setState(() => _tab = tab)),
        Expanded(
          child: switch (_tab) {
            DetailTab.transcript => TranscriptView(
              detail: detail,
              library: controller.speakers,
              onAssign: controller.assignSpeaker,
              player: _audioPlayer,
            ),
            DetailTab.overview => OverviewView(detail: detail),
          },
        ),
      ],
    );
  }
}

class _Tabs extends StatelessWidget {
  const _Tabs({required this.active, required this.onChanged});

  final DetailTab active;
  final ValueChanged<DetailTab> onChanged;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      decoration: BoxDecoration(
        color: p.surface,
        border: Border(bottom: BorderSide(color: p.border)),
      ),
      child: Row(
        children: [
          _Tab(label: 'Transcript', active: active == DetailTab.transcript, onTap: () => onChanged(DetailTab.transcript)),
          _Tab(label: 'Overview', active: active == DetailTab.overview, onTap: () => onChanged(DetailTab.overview)),
        ],
      ),
    );
  }
}

class _Tab extends StatelessWidget {
  const _Tab({required this.label, required this.active, required this.onTap});

  final String label;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            border: Border(bottom: BorderSide(color: active ? p.accent : Colors.transparent, width: 2)),
          ),
          child: Text(
            label,
            style: TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: active ? p.accent : p.text3),
          ),
        ),
      ),
    );
  }
}
