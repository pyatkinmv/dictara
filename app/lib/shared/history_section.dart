import 'dart:async';
import 'dart:convert';
// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:html' as html;

import 'package:flutter/material.dart';

import 'api_client.dart';
import 'auth_service.dart';
import 'models.dart';

// ── Tag colors ────────────────────────────────────────────────────────────────

Color _tagColor(String tag) {
  final hash = tag.runes.fold(0, (acc, r) => (acc * 31 + r) & 0x7FFFFFFF);
  final hue = (hash % 360).toDouble();
  return HSLColor.fromAHSL(1.0, hue, 0.55, 0.38).toColor();
}

Widget _tagChip(String tag, {VoidCallback? onDelete}) => Chip(
      label: Text(tag, style: const TextStyle(color: Colors.white, fontSize: 11)),
      backgroundColor: _tagColor(tag),
      visualDensity: VisualDensity.compact,
      padding: EdgeInsets.zero,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
      onDeleted: onDelete,
      deleteIconColor: Colors.white70,
    );

// ── HistorySection ────────────────────────────────────────────────────────────

class HistorySection extends StatefulWidget {
  final ApiClient api;
  final AuthService authService;

  const HistorySection({super.key, required this.api, required this.authService});

  @override
  State<HistorySection> createState() => HistorySectionState();
}

class HistorySectionState extends State<HistorySection> {
  List<HistoryItem> _items = [];
  String? _expandedJobId;
  final Map<String, JobResult> _cache = {};
  Timer? _pollTimer;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    widget.authService.addListener(_onAuthChanged);
    if (widget.authService.isLoggedIn) _loadHistory();
  }

  @override
  void dispose() {
    widget.authService.removeListener(_onAuthChanged);
    _pollTimer?.cancel();
    super.dispose();
  }

  void _onAuthChanged() {
    if (widget.authService.isLoggedIn) {
      _loadHistory();
    } else {
      _pollTimer?.cancel();
      setState(() {
        _items = [];
        _expandedJobId = null;
        _cache.clear();
      });
    }
  }

  Future<void> _loadHistory() async {
    setState(() => _loading = true);
    try {
      final items = await widget.api.fetchTranscriptions();
      if (mounted) setState(() { _items = items; _loading = false; });
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  void addItem(HistoryItem item) {
    setState(() => _items.insert(0, item));
  }

  Future<void> _toggleExpand(String jobId) async {
    _pollTimer?.cancel();
    if (_expandedJobId == jobId) {
      setState(() => _expandedJobId = null);
      return;
    }
    setState(() => _expandedJobId = jobId);

    final item = _items.firstWhere((i) => i.jobId == jobId);
    if (_cache.containsKey(jobId) && item.status == JobStatus.done) return;

    await _loadJobContent(jobId);

    final updated = _items.cast<HistoryItem?>().firstWhere((i) => i?.jobId == jobId, orElse: () => null);
    if (updated != null && updated.status != JobStatus.done && updated.status != JobStatus.failed) {
      _pollTimer = Timer.periodic(const Duration(seconds: 3), (_) => _pollExpanded(jobId));
    }
  }

  Future<void> _loadJobContent(String jobId) async {
    try {
      final result = await widget.api.pollJob(jobId);
      if (!mounted) return;
      setState(() {
        _cache[jobId] = result;
        final idx = _items.indexWhere((i) => i.jobId == jobId);
        if (idx != -1) {
          _items[idx].status = result.status;
          _items[idx].tags = result.tags;
        }
      });
    } catch (_) {}
  }

  Future<void> _pollExpanded(String jobId) async {
    if (_expandedJobId != jobId) { _pollTimer?.cancel(); return; }
    await _loadJobContent(jobId);
    final idx = _items.indexWhere((i) => i.jobId == jobId);
    if (idx != -1 && (_items[idx].status == JobStatus.done || _items[idx].status == JobStatus.failed)) {
      _pollTimer?.cancel();
    }
  }

  void _download(String jobId) {
    final result = _cache[jobId];
    if (result == null) return;
    final item = _items.firstWhere((i) => i.jobId == jobId);
    final text = result.toTranscriptText();
    final bytes = utf8.encode(text);
    final blob = html.Blob([bytes], 'text/plain');
    final url = html.Url.createObjectUrlFromBlob(blob);
    final baseName = item.fileName.contains('.')
        ? item.fileName.substring(0, item.fileName.lastIndexOf('.'))
        : item.fileName;
    html.AnchorElement(href: url)
      ..setAttribute('download', '$baseName.txt')
      ..click();
    html.Url.revokeObjectUrl(url);
  }

  Future<void> _onAddTag(String jobId, String tag) async {
    try {
      final tags = await widget.api.addTag(jobId, tag);
      if (!mounted) return;
      setState(() {
        final idx = _items.indexWhere((i) => i.jobId == jobId);
        if (idx >= 0) _items[idx].tags = tags;
        final cached = _cache[jobId];
        if (cached != null) _cache[jobId] = cached.copyWith(tags: tags);
      });
    } catch (_) {}
  }

  Future<void> _onRemoveTag(String jobId, String tag) async {
    try {
      final tags = await widget.api.removeTag(jobId, tag);
      if (!mounted) return;
      setState(() {
        final idx = _items.indexWhere((i) => i.jobId == jobId);
        if (idx >= 0) _items[idx].tags = tags;
        final cached = _cache[jobId];
        if (cached != null) _cache[jobId] = cached.copyWith(tags: tags);
      });
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.authService.isLoggedIn) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Divider(),
        const SizedBox(height: 8),
        Row(
          children: [
            Text('History', style: Theme.of(context).textTheme.titleSmall),
            const Spacer(),
            if (_loading)
              const SizedBox(
                width: 16, height: 16,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
          ],
        ),
        const SizedBox(height: 8),
        if (!_loading && _items.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 16),
            child: Center(
              child: Text('No transcriptions yet', style: TextStyle(color: Colors.grey)),
            ),
          ),
        for (final item in _items)
          _HistoryTile(
            item: item,
            isExpanded: _expandedJobId == item.jobId,
            cachedResult: _cache[item.jobId],
            onTap: () => _toggleExpand(item.jobId),
            onDownload: () => _download(item.jobId),
            onAddTag: (tag) => _onAddTag(item.jobId, tag),
            onRemoveTag: (tag) => _onRemoveTag(item.jobId, tag),
          ),
      ],
    );
  }
}

// ── _HistoryTile ──────────────────────────────────────────────────────────────

class _HistoryTile extends StatelessWidget {
  final HistoryItem item;
  final bool isExpanded;
  final JobResult? cachedResult;
  final VoidCallback onTap;
  final VoidCallback onDownload;
  final ValueChanged<String> onAddTag;
  final ValueChanged<String> onRemoveTag;

  const _HistoryTile({
    required this.item,
    required this.isExpanded,
    required this.cachedResult,
    required this.onTap,
    required this.onDownload,
    required this.onAddTag,
    required this.onRemoveTag,
  });

  @override
  Widget build(BuildContext context) {
    final isDone = item.status == JobStatus.done;
    final isInProgress = item.status == JobStatus.processing ||
        item.status == JobStatus.pending ||
        item.status == JobStatus.summarizing;

    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        children: [
          InkWell(
            onTap: onTap,
            borderRadius: isExpanded
                ? const BorderRadius.vertical(top: Radius.circular(12))
                : BorderRadius.circular(12),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          item.fileName,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Text(_formatDate(item.createdAt),
                          style: Theme.of(context).textTheme.bodySmall),
                      const SizedBox(width: 8),
                      SizedBox(
                        width: 28,
                        height: 28,
                        child: isDone
                            ? IconButton(
                                icon: const Icon(Icons.download, size: 16),
                                padding: EdgeInsets.zero,
                                onPressed: onDownload,
                                tooltip: 'Download',
                              )
                            : isInProgress
                                ? const Center(
                                    child: SizedBox(
                                      width: 16,
                                      height: 16,
                                      child: CircularProgressIndicator(strokeWidth: 2),
                                    ),
                                  )
                                : const SizedBox.shrink(),
                      ),
                      const SizedBox(width: 4),
                      Icon(
                        isExpanded ? Icons.expand_less : Icons.expand_more,
                        size: 20,
                      ),
                    ],
                  ),
                  if (item.tags.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: 4,
                      runSpacing: 4,
                      children: item.tags.map((t) => _tagChip(t)).toList(),
                    ),
                  ],
                ],
              ),
            ),
          ),
          if (isExpanded)
            _ExpandedContent(
              item: item,
              result: cachedResult,
              onAddTag: onAddTag,
              onRemoveTag: onRemoveTag,
            ),
        ],
      ),
    );
  }

  String _formatDate(DateTime dt) {
    final local = dt.toLocal();
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    final h = local.hour.toString().padLeft(2, '0');
    final m = local.minute.toString().padLeft(2, '0');
    return '${months[local.month - 1]} ${local.day}  $h:$m';
  }
}

// ── _ExpandedContent ──────────────────────────────────────────────────────────

class _ExpandedContent extends StatefulWidget {
  final HistoryItem item;
  final JobResult? result;
  final ValueChanged<String> onAddTag;
  final ValueChanged<String> onRemoveTag;

  const _ExpandedContent({
    required this.item,
    required this.result,
    required this.onAddTag,
    required this.onRemoveTag,
  });

  @override
  State<_ExpandedContent> createState() => _ExpandedContentState();
}

class _ExpandedContentState extends State<_ExpandedContent> {
  final _tagController = TextEditingController();

  @override
  void dispose() {
    _tagController.dispose();
    super.dispose();
  }

  void _submitTag() {
    final tag = _tagController.text.trim().toLowerCase();
    if (tag.isEmpty || !RegExp(r'^[\w-]{1,64}$').hasMatch(tag)) return;
    widget.onAddTag(tag);
    _tagController.clear();
  }

  @override
  Widget build(BuildContext context) {
    final result = widget.result;

    if (result == null) {
      return const Padding(
        padding: EdgeInsets.all(16),
        child: Center(child: CircularProgressIndicator()),
      );
    }

    if (result.status == JobStatus.failed) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              result.error ?? 'Transcription failed',
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
            const SizedBox(height: 12),
            _tagSection(context),
          ],
        ),
      );
    }

    if (result.status != JobStatus.done) {
      return _ProgressContent(result: result);
    }

    final text = result.toTranscriptText();
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
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
          const SizedBox(height: 12),
          const Divider(),
          const SizedBox(height: 8),
          _tagSection(context),
        ],
      ),
    );
  }

  Widget _tagSection(BuildContext context) {
    final tags = widget.item.tags;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (tags.isNotEmpty) ...[
          Wrap(
            spacing: 4,
            runSpacing: 4,
            children: tags
                .map((t) => _tagChip(t, onDelete: () => widget.onRemoveTag(t)))
                .toList(),
          ),
          const SizedBox(height: 8),
        ],
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: _tagController,
                decoration: const InputDecoration(
                  hintText: 'Add tag…',
                  isDense: true,
                  border: OutlineInputBorder(),
                  contentPadding: EdgeInsets.symmetric(horizontal: 8, vertical: 8),
                ),
                onSubmitted: (_) => _submitTag(),
              ),
            ),
            const SizedBox(width: 8),
            IconButton(
              icon: const Icon(Icons.add),
              onPressed: _submitTag,
              tooltip: 'Add tag',
            ),
          ],
        ),
      ],
    );
  }
}

// ── _ProgressContent ──────────────────────────────────────────────────────────

class _ProgressContent extends StatelessWidget {
  final JobResult result;
  const _ProgressContent({required this.result});

  @override
  Widget build(BuildContext context) {
    final p = result.progress;
    double? value;
    String label = '⏳ Processing…';
    if (p != null) {
      if (p.phase == 'summarizing') {
        label = '✍️ Summarizing…';
      } else if (p.phase == 'diarizing' && p.diarizeProgress != null) {
        value = p.diarizeProgress;
        label = '👥 Detecting speakers… ${(p.diarizeProgress! * 100).toStringAsFixed(0)}%';
      } else if (p.processedS != null && p.totalS != null && p.totalS! > 0) {
        value = p.processedS! / p.totalS!;
        label = '🎙 Transcribing… ${(value * 100).toStringAsFixed(0)}%';
      }
    }
    return Padding(
      padding: const EdgeInsets.all(12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(label),
          const SizedBox(height: 8),
          LinearProgressIndicator(value: value),
        ],
      ),
    );
  }
}
