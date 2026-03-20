import 'dart:async';
import 'dart:convert';
import 'dart:math';
// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:html' as html;
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../shared/api_client.dart';
import '../shared/auth_service.dart';
import '../shared/history_section.dart';
import '../shared/models.dart';

enum _State { idle, uploading, processing, done, error }

class TranscribePage extends StatefulWidget {
  final AuthService authService;
  final ApiClient api;

  const TranscribePage({super.key, required this.authService, required this.api});

  @override
  State<TranscribePage> createState() => _TranscribePageState();
}

class _TranscribePageState extends State<TranscribePage> {
  ApiClient get _api => widget.api;

  final _historyKey = GlobalKey<HistorySectionState>();

  // Health
  bool _online = false;
  Timer? _healthTimer;

  // Settings
  String _model = 'small';
  String _language = 'auto';
  bool _diarize = false;
  int? _numSpeakers;
  String _summaryMode = 'auto';

  // File
  Uint8List? _fileBytes;
  String? _fileName;
  List<String> _supportedExtensions = ['mp3', 'mp4', 'wav', 'ogg', 'm4a', 'flac', 'webm', 'mkv', 'avi', 'mov'];

  // Job state
  _State _state = _State.idle;
  JobResult? _jobResult;
  String? _errorMsg;
  String? _jobId;
  Timer? _pollTimer;
  ProgressInfo? _progress;
  int? _queuePosition;

  // Backoff state
  DateTime? _firstFailureAt;
  Duration _pollInterval = const Duration(seconds: 3);
  String? _reconnectingMsg;

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
      _firstFailureAt = null;
      _pollInterval = const Duration(seconds: 3);
      _reconnectingMsg = null;
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
      // Successful response — reset backoff
      setState(() {
        _progress = result.progress;
        _queuePosition = result.queuePosition;
        _firstFailureAt = null;
        _pollInterval = const Duration(seconds: 3);
        _reconnectingMsg = null;
      });

      if (result.status == JobStatus.done) {
        _pollTimer?.cancel();
        setState(() {
          _state = _State.done;
          _jobResult = result;
        });
        _historyKey.currentState?.addItem(HistoryItem(
          jobId: _jobId!,
          fileName: _fileName!,
          createdAt: DateTime.now(),
          status: JobStatus.done,
        ));
      } else if (result.status == JobStatus.failed) {
        _pollTimer?.cancel();
        setState(() {
          _state = _State.error;
          _errorMsg = result.error ?? 'Transcription failed';
        });
      }
    } catch (e) {
      if (!mounted) return;
      final now = DateTime.now();
      _firstFailureAt ??= now;
      if (now.difference(_firstFailureAt!).inMinutes >= 10) {
        _pollTimer?.cancel();
        setState(() {
          _state = _State.error;
          _errorMsg = 'Server unavailable for too long. Please try again.';
        });
        return;
      }
      _pollInterval = Duration(
        milliseconds: min(_pollInterval.inMilliseconds * 2, 300000),
      );
      _pollTimer?.cancel();
      _pollTimer = Timer.periodic(_pollInterval, (_) => _poll());
      setState(() {
        _reconnectingMsg = '⚠️ Server temporarily unavailable, retrying…';
      });
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
    return Scaffold(
      appBar: AppBar(
        title: const Text('Dictara'),
        actions: [
          ListenableBuilder(
            listenable: widget.authService,
            builder: (context, _) {
              if (widget.authService.isLoggedIn) {
                return Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 8),
                      child: Text(
                        widget.authService.displayName ?? 'Logged in',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.logout),
                      tooltip: 'Logout',
                      onPressed: widget.authService.logout,
                    ),
                  ],
                );
              }
              return TextButton.icon(
                icon: const Icon(Icons.account_circle_outlined),
                label: const Text('Login with Telegram'),
                onPressed: () => widget.authService.triggerLogin(context, widget.api),
              );
            },
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Chip(
              avatar: Icon(
                Icons.circle,
                size: 10,
                color: _online ? Colors.green : Colors.red,
              ),
              label: Text(_online ? 'online' : 'offline'),
              visualDensity: VisualDensity.compact,
            ),
          ),
        ],
      ),
      body: SingleChildScrollView(
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
                  _ProgressSection(progress: _progress, queuePosition: _queuePosition, reconnectingMsg: _reconnectingMsg),
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
                const SizedBox(height: 24),
                HistorySection(
                  key: _historyKey,
                  api: _api,
                  authService: widget.authService,
                ),
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
        _LabeledDropdown<String>(
          label: 'Model',
          value: model,
          items: _kModels,
          enabled: enabled,
          onChanged: onModelChanged,
        ),
        _LabeledDropdown<String>(
          label: 'Language',
          value: language,
          items: _kLanguages,
          enabled: enabled,
          onChanged: onLanguageChanged,
        ),
        _LabeledDropdown<String>(
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
            Switch(
              value: diarize,
              onChanged: enabled ? onDiarizeChanged : null,
            ),
          ],
        ),
        if (diarize)
          _LabeledDropdown<int?>(
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

class _LabeledDropdown<T> extends StatelessWidget {
  final String label;
  final T value;
  final Map<T, String> items;
  final bool enabled;
  final ValueChanged<T> onChanged;

  const _LabeledDropdown({
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
        DropdownButton<T>(
          value: value,
          isDense: true,
          onChanged: enabled ? (v) => onChanged(v as T) : null,
          items: items.entries
              .map((e) => DropdownMenuItem<T>(value: e.key, child: Text(e.value)))
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
    final theme = Theme.of(context);
    return InkWell(
      onTap: enabled ? onPickFile : null,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        height: 120,
        decoration: BoxDecoration(
          border: Border.all(
            color: theme.colorScheme.outline,
            width: 1.5,
            // dashed look via strokeAlign workaround not available in Container,
            // using solid with low opacity instead
          ),
          borderRadius: BorderRadius.circular(12),
          color: enabled ? theme.colorScheme.surfaceContainerLow : theme.colorScheme.surfaceContainerLowest,
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              fileName != null ? Icons.audio_file : Icons.upload_file,
              size: 32,
              color: theme.colorScheme.primary,
            ),
            const SizedBox(height: 8),
            Text(
              fileName ?? 'Click to choose an audio or video file',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: fileName != null ? theme.colorScheme.primary : theme.colorScheme.onSurfaceVariant,
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
        LinearProgressIndicator(),
        SizedBox(height: 8),
        Text('Uploading…'),
      ],
    );
  }
}

// ── Progress ──────────────────────────────────────────────────────────────────

class _ProgressSection extends StatelessWidget {
  final ProgressInfo? progress;
  final int? queuePosition;
  final String? reconnectingMsg;

  const _ProgressSection({required this.progress, this.queuePosition, this.reconnectingMsg});

  @override
  Widget build(BuildContext context) {
    final p = progress;
    double? value;
    String label = '⏳ Processing…';

    String? queueLabel = queuePosition != null ? 'Position in queue: $queuePosition' : null;

    if (p == null) {
      label = queueLabel != null ? '⏳ $queueLabel' : '⏳ Processing…';
      queueLabel = null; // already in main label, don't show twice
    } else {
      if (p.phase == 'summarizing') {
        label = '✍️ Summarizing…';
      } else if (p.phase == 'diarizing' && p.diarizeProgress != null) {
        value = p.diarizeProgress;
        label = '👥 Detecting speakers… ${(p.diarizeProgress! * 100).toStringAsFixed(0)}%';
      } else if (p.processedS != null && p.totalS != null && p.totalS! > 0) {
        value = p.processedS! / p.totalS!;
        label =
            '🎙 Transcribing… ${p.processedS!.toStringAsFixed(0)} / ${p.totalS!.toStringAsFixed(0)} s  (${(value * 100).toStringAsFixed(0)}%)';
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
            LinearProgressIndicator(value: value),
            if (queueLabel != null) ...[
              const SizedBox(height: 4),
              Text(queueLabel, style: const TextStyle(fontSize: 12)),
            ],
            if (reconnectingMsg != null) ...[
              const SizedBox(height: 8),
              Text(
                reconnectingMsg!,
                style: const TextStyle(color: Colors.orange, fontSize: 12),
              ),
            ],
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
                const Icon(Icons.check_circle, color: Colors.green),
                const SizedBox(width: 8),
                Text('Done$durationLabel', style: Theme.of(context).textTheme.titleSmall),
                const Spacer(),
                FilledButton.tonal(
                  onPressed: onDownload,
                  child: const Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [Icon(Icons.download, size: 18), SizedBox(width: 4), Text('Download')],
                  ),
                ),
                const SizedBox(width: 8),
                OutlinedButton(onPressed: onReset, child: const Text('New')),
              ],
            ),
            const SizedBox(height: 12),
            Container(
              height: 300,
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerLow,
                borderRadius: BorderRadius.circular(8),
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
              Text('Summary', style: Theme.of(context).textTheme.labelLarge),
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
      color: Theme.of(context).colorScheme.errorContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(Icons.error_outline, color: Theme.of(context).colorScheme.onErrorContainer),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                message,
                style: TextStyle(color: Theme.of(context).colorScheme.onErrorContainer),
              ),
            ),
            const SizedBox(width: 12),
            OutlinedButton(onPressed: onRetry, child: const Text('Retry')),
          ],
        ),
      ),
    );
  }
}
