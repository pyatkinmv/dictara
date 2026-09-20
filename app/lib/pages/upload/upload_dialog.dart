import 'package:cross_file/cross_file.dart';
import 'package:desktop_drop/desktop_drop.dart';
import 'package:flutter/material.dart';

import '../../models/upload.dart';
import '../../services/file_chooser.dart';
import '../../theme/app_palette.dart';
import '../../util/format.dart';
import '../../widgets/dashed_border.dart';
import '../../widgets/modal.dart';

/// The "Upload audio" dialog (`#upload-modal`): a drop zone and three options.
/// Returns what to upload, or null if it was cancelled.
Future<UploadRequest?> showUploadDialog(
  BuildContext context, {
  required FileChooser chooser,
}) {
  return showAppModal<UploadRequest>(
    context,
    builder: (context) => _UploadDialog(chooser: chooser),
  );
}

class _UploadDialog extends StatefulWidget {
  const _UploadDialog({required this.chooser});

  final FileChooser chooser;

  @override
  State<_UploadDialog> createState() => _UploadDialogState();
}

class _UploadDialogState extends State<_UploadDialog> {
  ChosenFile? _file;
  UploadLanguage _language = UploadLanguage.auto;
  SummaryMode _summary = SummaryMode.auto;
  SpeakerCount _speakers = SpeakerCount.auto;

  /// Why [_file] cannot be uploaded.
  String? get _problem => _file?.problem;

  bool get _canUpload => _file != null && _problem == null;

  Future<void> _browse() async {
    final file = await widget.chooser.choose();
    if (file != null && mounted) setState(() => _file = file);
  }

  /// A file dropped on the zone; several at once are not supported, the first wins.
  Future<void> _dropped(List<XFile> files) async {
    final first = files.firstOrNull;
    if (first == null) return;
    final size = await first.length();
    if (mounted) {
      setState(() => _file = ChosenFile(name: first.name, sizeBytes: size));
    }
  }

  void _upload() {
    if (!_canUpload) return;
    Navigator.of(context).pop(
      UploadRequest(
        file: _file!,
        language: _language,
        summary: _summary,
        speakers: _speakers,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ModalCard(
      width: 500,
      radius: 20,
      shadow: const BoxShadow(
        color: Color(0x26000000),
        blurRadius: 60,
        offset: Offset(0, 20),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ModalHeader(
            title: 'Upload audio',
            onClose: () => Navigator.of(context).pop(),
          ),
          _DropZone(
            file: _file,
            problem: _problem,
            onTap: _browse,
            onDropped: _dropped,
          ),
          Padding(
            padding: const EdgeInsets.only(bottom: 18),
            child: IntrinsicHeight(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: _SelectField<UploadLanguage>(
                      label: 'Language',
                      value: _language,
                      values: UploadLanguage.values,
                      labelOf: (v) => v.label,
                      onChanged: (v) => setState(() => _language = v),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: _SelectField<SummaryMode>(
                      label: 'Summary',
                      value: _summary,
                      values: SummaryMode.values,
                      labelOf: (v) => v.label,
                      onChanged: (v) => setState(() => _summary = v),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: _SelectField<SpeakerCount>(
                      label: 'Speakers',
                      value: _speakers,
                      values: SpeakerCount.values,
                      labelOf: (v) => v.label,
                      onChanged: (v) => setState(() => _speakers = v),
                    ),
                  ),
                ],
              ),
            ),
          ),
          ModalFooter(
            buttons: [
              ModalButton(
                label: 'Cancel',
                kind: ModalButtonKind.cancel,
                onPressed: () => Navigator.of(context).pop(),
              ),
              // Unlike the design's, this button waits for a valid file.
              Opacity(
                opacity: _canUpload ? 1 : .5,
                child: ModalButton(label: 'Upload', onPressed: _upload),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// The dashed area you click to choose a file (`.drop-zone`).
class _DropZone extends StatefulWidget {
  const _DropZone({
    required this.file,
    required this.problem,
    required this.onTap,
    required this.onDropped,
  });

  final ChosenFile? file;
  final String? problem;
  final VoidCallback onTap;

  /// Files dragged onto the zone from outside the window.
  final ValueChanged<List<XFile>> onDropped;

  @override
  State<_DropZone> createState() => _DropZoneState();
}

class _DropZoneState extends State<_DropZone> {
  bool _hover = false;
  bool _dragging = false; // a file is being dragged over the zone

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final file = widget.file;
    final problem = widget.problem;
    final highlighted =
        _hover || _dragging || (file != null && problem == null);
    final borderColor = problem != null
        ? p.danger
        : (highlighted ? p.accent : p.border);

    final Widget content;
    if (file == null) {
      content = Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.upload_file_rounded,
            size: 32,
            color: p.accent.withValues(alpha: .5),
          ),
          const SizedBox(height: 7),
          Text(
            'Drop file here or click to browse',
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              height: 1.21,
              color: p.text2,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            'MP3, MP4, M4A, OGG, WAV, FLAC · max 400 MB',
            style: TextStyle(fontSize: 12, color: p.text3),
          ),
        ],
      );
    } else {
      content = Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.audio_file_rounded,
            size: 32,
            color: problem != null ? p.danger : p.accent,
          ),
          const SizedBox(height: 7),
          Text(
            file.name,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              height: 1.21,
              color: p.text,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            problem ??
                '${formatFileSize(file.sizeBytes)} · click to choose another file',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 12,
              color: problem != null ? p.danger : p.text3,
            ),
          ),
        ],
      );
    }

    return DropTarget(
      onDragEntered: (_) => setState(() => _dragging = true),
      onDragExited: (_) => setState(() => _dragging = false),
      onDragDone: (details) {
        setState(() => _dragging = false);
        widget.onDropped(details.files);
      },
      child: Padding(
        padding: const EdgeInsets.only(bottom: 14),
        child: MouseRegion(
          cursor: SystemMouseCursors.click,
          onEnter: (_) => setState(() => _hover = true),
          onExit: (_) => setState(() => _hover = false),
          child: GestureDetector(
            onTap: widget.onTap,
            child: DashedBorder(
              color: borderColor,
              width: 2,
              radius: 12,
              dash: 6,
              gap: 4,
              child: Container(
                padding: const EdgeInsets.all(28),
                decoration: BoxDecoration(
                  color: highlighted && problem == null
                      ? p.accentLight
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(10),
                ),
                alignment: Alignment.center,
                child: content,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Caption above a select (`.m-field`).
class _SelectField<T> extends StatelessWidget {
  const _SelectField({
    required this.label,
    required this.value,
    required this.values,
    required this.labelOf,
    required this.onChanged,
  });

  final String label;
  final T value;
  final List<T> values;
  final String Function(T) labelOf;
  final ValueChanged<T> onChanged;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          label.toUpperCase(),
          style: TextStyle(
            fontSize: 11,
            height: 14 / 11,
            fontWeight: FontWeight.w700,
            letterSpacing: .66,
            color: p.text3,
          ),
        ),
        const SizedBox(height: 5),
        Container(
          // 7 px padding around a 16 px line, plus the 1 px border.
          height: 34,
          padding: const EdgeInsets.symmetric(horizontal: 10),
          decoration: BoxDecoration(
            color: p.background,
            border: Border.all(color: p.border),
            borderRadius: BorderRadius.circular(9),
          ),
          child: DropdownButtonHideUnderline(
            child: DropdownButton<T>(
              value: value,
              isExpanded: true,
              isDense: true,
              itemHeight: null,
              icon: Icon(Icons.expand_more_rounded, size: 18, color: p.text3),
              dropdownColor: p.surface,
              borderRadius: BorderRadius.circular(10),
              style: TextStyle(fontSize: 13, color: p.text),
              items: [
                for (final v in values)
                  DropdownMenuItem<T>(value: v, child: Text(labelOf(v))),
              ],
              onChanged: (v) {
                if (v != null) onChanged(v);
              },
            ),
          ),
        ),
      ],
    );
  }
}
