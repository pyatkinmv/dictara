import 'dart:async';
import 'dart:convert';
// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:html' as html;
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:fluent_ui/fluent_ui.dart';

import '../shared/api_client.dart';
import '../shared/models.dart';

enum _State { idle, uploading, processing, done, error }

class TranscribePageFluent extends StatefulWidget {
  const TranscribePageFluent({super.key});

  @override
  State<TranscribePageFluent> createState() => _TranscribePageFluentState();
}

class _TranscribePageFluentState extends State<TranscribePageFluent> {
  final _api = ApiClient();

  bool _online = false;
  Timer? _healthTimer;

  String _model = 'small';
  String _language = 'auto';
  bool _diarize = false;
  int? _numSpeakers;
  String _summaryMode = 'auto';

  Uint8List? _fileBytes;
  String? _fileName;
  List<String> _supportedExtensions = ['mp3', 'mp4', 'wav', 'ogg', 'm4a', 'flac', 'webm', 'mkv', 'avi', 'mov'];

  _State _state = _State.idle;
  JobResult? _jobResult;
  String? _errorMsg;
  String? _jobId;
  Timer? _pollTimer;
  ProgressInfo? _progress;

  @override
  void initState() {
    super.initState();
    _checkHealth();
    _healthTimer = Timer.periodic(const Duration(seconds: 15), (_) => _checkHealth());
    _loadSupportedExtensions();
  }

  Future<void> _loadSupportedExtensions() async {
    final exts = await _api.fetchSupportedExtensions();
    if (mounted) setState(() => _supportedExtensions = exts);
  }

  @override
  void dispose() {
    _healthTimer?.cancel();
    _pollTimer?.cancel();
    super.dispose();
  }

  Future<void> _checkHealth() async {
    final ok = await _api.checkHealth();
    if (mounted) setState(() => _online = ok);
  }

  Future<void> _pickFile() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: _supportedExtensions,
      withData: true,
    );
    if (result == null || result.files.isEmpty) return;
    final file = result.files.first;
    setState(() {
      _fileBytes = file.bytes;
      _fileName = file.name;
    });
  }

  Future<void> _startTranscription() async {
    if (_fileBytes == null || _fileName == null) return;
    setState(() {
      _state = _State.uploading;
      _errorMsg = null;
      _jobResult = null;
      _progress = null;
    });
    try {
      final jobId = await _api.submitJob(
        fileBytes: _fileBytes!,
        fileName: _fileName!,
        model: _model,
        language: _language,
        diarize: _diarize,
        numSpeakers: _numSpeakers,
        summaryMode: _summaryMode,
      );
      _jobId = jobId;
      setState(() => _state = _State.processing);
      _pollTimer = Timer.periodic(const Duration(seconds: 3), (_) => _poll());
    } catch (e) {
      setState(() {
        _state = _State.error;
        _errorMsg = e.toString();
      });
    }
  }

  Future<void> _poll() async {
    if (_jobId == null) return;
    try {
      final result = await _api.pollJob(_jobId!);
      if (!mounted) return;
      setState(() => _progress = result.progress);
      if (result.status == JobStatus.done) {
        _pollTimer?.cancel();
        setState(() {
          _state = _State.done;
          _jobResult = result;
        });
      } else if (result.status == JobStatus.failed) {
        _pollTimer?.cancel();
        setState(() {
          _state = _State.error;
          _errorMsg = result.error ?? 'Transcription failed';
        });
      }
    } catch (e) {
      _pollTimer?.cancel();
      if (mounted) setState(() { _state = _State.error; _errorMsg = e.toString(); });
    }
  }

  void _downloadTranscript() {
    final text = _jobResult?.toTranscriptText() ?? '';
    final bytes = utf8.encode(text);
    final blob = html.Blob([bytes], 'text/plain');
    final url = html.Url.createObjectUrlFromBlob(blob);
    html.AnchorElement(href: url)
      ..setAttribute('download', 'transcript.txt')
      ..click();
    html.Url.revokeObjectUrl(url);
  }

  void _reset() {
    _pollTimer?.cancel();
    setState(() {
      _state = _State.idle;
      _errorMsg = null;
      _jobResult = null;
      _progress = null;
      _jobId = null;
      _fileBytes = null;
      _fileName = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    return ScaffoldPage(
      header: PageHeader(
        title: const Text('Dictara'),
        commandBar: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              FluentIcons.circle_fill,
              size: 10,
              color: _online ? Colors.green : Colors.red,
            ),
            const SizedBox(width: 6),
            Text(_online ? 'online' : 'offline'),
            const SizedBox(width: 16),
          ],
        ),
      ),
      content: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 640),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _SettingsBar(
                  model: _model,
                  language: _language,
                  diarize: _diarize,
                  numSpeakers: _numSpeakers,
                  summaryMode: _summaryMode,
                  enabled: _state == _State.idle,
                  onModelChanged: (v) => setState(() => _model = v),
                  onLanguageChanged: (v) => setState(() => _language = v),
                  onDiarizeChanged: (v) => setState(() {
                    _diarize = v;
                    if (!v) _numSpeakers = null;
                  }),
                  onNumSpeakersChanged: (v) => setState(() => _numSpeakers = v),
                  onSummaryModeChanged: (v) => setState(() => _summaryMode = v),
                ),
                const SizedBox(height: 24),
                _DropZone(
                  fileName: _fileName,
                  enabled: _state == _State.idle,
                  onPickFile: _pickFile,
                ),
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: (_state == _State.idle && _fileBytes != null) ? _startTranscription : null,
                  child: const Text('Transcribe'),
                ),
                if (_state == _State.uploading) ...[
                  const SizedBox(height: 24),
                  const _UploadingSection(),
                ],
                if (_state == _State.processing) ...[
                  const SizedBox(height: 24),
                  _ProgressSection(progress: _progress),
                ],
                if (_state == _State.done && _jobResult != null) ...[
                  const SizedBox(height: 24),
                  _ResultSection(
                    result: _jobResult!,
                    onDownload: _downloadTranscript,
                    onReset: _reset,
                  ),
                ],
                if (_state == _State.error) ...[
                  const SizedBox(height: 24),
                  _ErrorSection(message: _errorMsg ?? 'Unknown error', onRetry: _reset),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ── Settings bar ─────────────────────────────────────────────────────────────

const _kModels = {'small': 'Fast (small)', 'large-v3': 'Accurate (large-v3)'};

const _kSummaryModes = {
  'off': 'Off',
  'auto': 'Auto',
  'brief': 'Brief',
  'concise': 'Concise',
  'full': 'Full',
};

const _kLanguages = {
  'auto': 'Auto-detect',
  'en': 'English',
  'ru': 'Russian',
  'de': 'German',
  'fr': 'French',
  'es': 'Spanish',
  'it': 'Italian',
  'pt': 'Portuguese',
  'zh': 'Chinese',
  'ja': 'Japanese',
  'ko': 'Korean',
  'ar': 'Arabic',
  'tr': 'Turkish',
  'pl': 'Polish',
  'nl': 'Dutch',
  'sv': 'Swedish',
  'uk': 'Ukrainian',
};

class _SettingsBar extends StatelessWidget {
  final String model;
  final String language;
  final bool diarize;
  final int? numSpeakers;
  final String summaryMode;
  final bool enabled;
  final ValueChanged<String> onModelChanged;
  final ValueChanged<String> onLanguageChanged;
  final ValueChanged<bool> onDiarizeChanged;
  final ValueChanged<int?> onNumSpeakersChanged;
  final ValueChanged<String> onSummaryModeChanged;

  const _SettingsBar({
    required this.model,
    required this.language,
    required this.diarize,
    required this.numSpeakers,
    required this.summaryMode,
    required this.enabled,
    required this.onModelChanged,
    required this.onLanguageChanged,
    required this.onDiarizeChanged,
    required this.onNumSpeakersChanged,
    required this.onSummaryModeChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 12,
      runSpacing: 8,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        _LabeledComboBox<String>(
          label: 'Model',
          value: model,
          items: _kModels,
          enabled: enabled,
          onChanged: onModelChanged,
        ),
        _LabeledComboBox<String>(
          label: 'Language',
          value: language,
          items: _kLanguages,
          enabled: enabled,
          onChanged: onLanguageChanged,
        ),
        _LabeledComboBox<String>(
          label: 'Summary',
          value: summaryMode,
          items: _kSummaryModes,
          enabled: enabled,
          onChanged: onSummaryModeChanged,
        ),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('Diarize'),
            const SizedBox(width: 8),
            ToggleSwitch(
              checked: diarize,
              onChanged: enabled ? onDiarizeChanged : null,
            ),
          ],
        ),
        if (diarize)
          _LabeledComboBox<int?>(
            label: 'Speakers',
            value: numSpeakers,
            items: {null: 'Auto', 2: '2', 3: '3', 4: '4', 5: '5', 6: '6'},
            enabled: enabled,
            onChanged: onNumSpeakersChanged,
          ),
      ],
    );
  }
}

class _LabeledComboBox<T> extends StatelessWidget {
  final String label;
  final T value;
  final Map<T, String> items;
  final bool enabled;
  final ValueChanged<T> onChanged;

  const _LabeledComboBox({
    required this.label,
    required this.value,
    required this.items,
    required this.enabled,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('$label: '),
        ComboBox<T>(
          value: value,
          onChanged: enabled ? (v) => onChanged(v as T) : null,
          items: items.entries
              .map((e) => ComboBoxItem<T>(value: e.key, child: Text(e.value)))
              .toList(),
        ),
      ],
    );
  }
}

// ── Drop zone ─────────────────────────────────────────────────────────────────

class _DropZone extends StatelessWidget {
  final String? fileName;
  final bool enabled;
  final VoidCallback onPickFile;

  const _DropZone({required this.fileName, required this.enabled, required this.onPickFile});

  @override
  Widget build(BuildContext context) {
    final theme = FluentTheme.of(context);
    return GestureDetector(
      onTap: enabled ? onPickFile : null,
      child: Container(
        height: 120,
        decoration: BoxDecoration(
          border: Border.all(color: theme.accentColor, width: 1.5),
          borderRadius: BorderRadius.circular(4),
          color: theme.micaBackgroundColor,
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              fileName != null ? FluentIcons.music_note : FluentIcons.upload,
              size: 32,
              color: theme.accentColor,
            ),
            const SizedBox(height: 8),
            Text(
              fileName ?? 'Click to choose an audio or video file',
              style: TextStyle(
                color: fileName != null ? theme.accentColor : theme.inactiveColor,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Uploading ─────────────────────────────────────────────────────────────────

class _UploadingSection extends StatelessWidget {
  const _UploadingSection();

  @override
  Widget build(BuildContext context) {
    return const Column(
      children: [
        ProgressBar(),
        SizedBox(height: 8),
        Text('Uploading…'),
      ],
    );
  }
}

// ── Progress ──────────────────────────────────────────────────────────────────

class _ProgressSection extends StatelessWidget {
  final ProgressInfo? progress;

  const _ProgressSection({required this.progress});

  @override
  Widget build(BuildContext context) {
    final p = progress;
    double? value;
    String label = '⏳ Processing…';

    if (p != null) {
      if (p.phase == 'summarizing') {
        label = '✍️ Summarizing…';
      } else if (p.phase == 'diarizing' && p.diarizeProgress != null) {
        value = p.diarizeProgress! * 100;
        label = '👥 Detecting speakers… ${p.diarizeProgress! * 100 ~/ 1}%';
      } else if (p.processedS != null && p.totalS != null && p.totalS! > 0) {
        value = (p.processedS! / p.totalS!) * 100;
        label =
            '🎙 Transcribing… ${p.processedS!.toStringAsFixed(0)} / ${p.totalS!.toStringAsFixed(0)} s  (${value.toStringAsFixed(0)}%)';
      }
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(label),
            const SizedBox(height: 8),
            ProgressBar(value: value),
          ],
        ),
      ),
    );
  }
}

// ── Result ────────────────────────────────────────────────────────────────────

class _ResultSection extends StatelessWidget {
  final JobResult result;
  final VoidCallback onDownload;
  final VoidCallback onReset;

  const _ResultSection({required this.result, required this.onDownload, required this.onReset});

  @override
  Widget build(BuildContext context) {
    final text = result.toTranscriptText();
    final durationLabel = result.audioDurationS != null
        ? ' · ${result.audioDurationS!.toStringAsFixed(0)} s audio'
        : '';

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(FluentIcons.check_mark, color: Colors.green),
                const SizedBox(width: 8),
                Text('Done$durationLabel',
                    style: FluentTheme.of(context).typography.bodyStrong),
                const Spacer(),
                FilledButton(
                  onPressed: onDownload,
                  child: const Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(FluentIcons.download, size: 16),
                      SizedBox(width: 4),
                      Text('Download'),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                Button(onPressed: onReset, child: const Text('New')),
              ],
            ),
            const SizedBox(height: 12),
            Container(
              height: 300,
              decoration: BoxDecoration(
                color: FluentTheme.of(context).micaBackgroundColor,
                borderRadius: BorderRadius.circular(4),
              ),
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(12),
                child: SelectableText(
                  text.isEmpty ? '(empty transcript)' : text,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                ),
              ),
            ),
            if (result.summary != null && result.summary!.isNotEmpty) ...[
              const SizedBox(height: 12),
              const Divider(),
              const SizedBox(height: 8),
              Text('Summary',
                  style: FluentTheme.of(context).typography.bodyStrong),
              const SizedBox(height: 6),
              SelectableText(result.summary!),
            ],
          ],
        ),
      ),
    );
  }
}

// ── Error ─────────────────────────────────────────────────────────────────────

class _ErrorSection extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;

  const _ErrorSection({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    return Card(
      backgroundColor: Colors.red.withValues(alpha: 0.12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(FluentIcons.error_badge, color: Colors.red),
            const SizedBox(width: 12),
            Expanded(child: Text(message)),
            const SizedBox(width: 12),
            Button(onPressed: onRetry, child: const Text('Retry')),
          ],
        ),
      ),
    );
  }
}
