import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../data/transcription_repository.dart';
import '../models/transcription.dart';
import '../state/transcription_list_controller.dart';
import '../theme/app_palette.dart';
import '../widgets/form_marks.dart';
import '../widgets/mini_tag.dart';
import '../widgets/popover.dart';
import '../widgets/status_badge.dart';
import '../widgets/transcription_row.dart';

/// The home screen: search, filter and sort bar above the list of transcriptions.
class TranscriptionsPage extends StatefulWidget {
  const TranscriptionsPage({super.key});

  @override
  State<TranscriptionsPage> createState() => _TranscriptionsPageState();
}

class _TranscriptionsPageState extends State<TranscriptionsPage> {
  late final TextEditingController _search;

  @override
  void initState() {
    super.initState();
    // The list controller outlives the page, so restore the search text on return.
    _search = TextEditingController(text: context.read<TranscriptionListController>().query.search);
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final list = context.watch<TranscriptionListController>();
    final p = context.palette;

    // The query can be reset from outside (a new upload clears the search).
    final wanted = list.query.search;
    if (_search.text != wanted) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _search.text != wanted) _search.text = wanted;
      });
    }

    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(24, 18, 24, 0),
          sliver: SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 14),
              child: _FilterBar(searchController: _search),
            ),
          ),
        ),
        if (!list.loading && list.items.isEmpty)
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 56),
              child: Center(
                child: Text('No matching transcriptions', style: TextStyle(fontSize: 14, color: p.text3)),
              ),
            ),
          )
        else
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(24, 0, 24, 18),
            sliver: SliverList.separated(
              itemCount: list.items.length,
              separatorBuilder: (_, _) => const SizedBox(height: 7),
              itemBuilder: (context, i) {
                final item = list.items[i];
                return TranscriptionRow(
                  item: item,
                  // Only finished transcriptions have a detail page.
                  onTap: () {
                    if (item.status == TranscriptionStatus.done) {
                      context.go('/transcriptions/${item.id}');
                    }
                  },
                );
              },
            ),
          ),
      ],
    );
  }
}

class _FilterBar extends StatelessWidget {
  const _FilterBar({required this.searchController});

  final TextEditingController searchController;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: _SearchField(controller: searchController)),
        const SizedBox(width: 8),
        const _FilterDropdown(),
        const SizedBox(width: 8),
        const _SortDropdown(),
      ],
    );
  }
}

class _SearchField extends StatelessWidget {
  const _SearchField({required this.controller});

  final TextEditingController controller;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    OutlineInputBorder border(Color color) => OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide: BorderSide(color: color),
        );
    return TextField(
      controller: controller,
      onChanged: context.read<TranscriptionListController>().setSearch,
      cursorColor: p.accent,
      style: TextStyle(fontSize: 13, color: p.text),
      decoration: InputDecoration(
        isDense: true,
        filled: true,
        fillColor: p.surface,
        hintText: 'Search transcriptions…',
        hintStyle: TextStyle(fontSize: 13, color: p.text3),
        prefixIcon: Padding(
          padding: const EdgeInsets.only(left: 10, right: 8),
          child: Icon(Icons.search_rounded, size: 16, color: p.text3),
        ),
        prefixIconConstraints: const BoxConstraints(minWidth: 34, minHeight: 16),
        contentPadding: const EdgeInsets.only(right: 12, top: 8, bottom: 8),
        border: border(p.border),
        enabledBorder: border(p.border),
        focusedBorder: border(p.accent),
      ),
    );
  }
}

/// Bordered pill button that opens a dropdown (`.dd-btn`).
class _ToolbarButton extends StatefulWidget {
  const _ToolbarButton({
    required this.icon,
    required this.label,
    required this.active,
    required this.onTap,
  });

  final IconData icon;
  final String label;

  /// A non-default filter/sort is applied.
  final bool active;
  final VoidCallback onTap;

  @override
  State<_ToolbarButton> createState() => _ToolbarButtonState();
}

class _ToolbarButtonState extends State<_ToolbarButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final highlighted = widget.active || _hover;
    final color = highlighted ? p.accent : p.text2;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
          decoration: BoxDecoration(
            color: widget.active ? p.accentLight : p.surface,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: highlighted ? p.accent : p.border),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(widget.icon, size: 16, color: color),
              const SizedBox(width: 5),
              Text(
                widget.label,
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: color),
              ),
              const SizedBox(width: 5),
              Icon(Icons.expand_more_rounded, size: 16, color: color),
            ],
          ),
        ),
      ),
    );
  }
}

class _FilterDropdown extends StatelessWidget {
  const _FilterDropdown();

  @override
  Widget build(BuildContext context) {
    final count = context.select<TranscriptionListController, int>((c) => c.query.activeFilterCount);
    return Popover(
      minWidth: 200,
      targetBuilder: (context, open, toggle) => _ToolbarButton(
        icon: Icons.filter_list_rounded,
        label: count > 0 ? 'Filter · $count' : 'Filter',
        active: count > 0,
        onTap: toggle,
      ),
      panelBuilder: (context, close) => Consumer<TranscriptionListController>(
        builder: (context, c, _) => PopoverPanel(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const PopoverSection('Status'),
              for (final status in TranscriptionStatus.values)
                PopoverRow(
                  onTap: () => c.toggleStatus(status),
                  child: Row(
                    children: [
                      CheckMark(checked: c.query.statuses.contains(status)),
                      const SizedBox(width: 8),
                      Text(status.label),
                    ],
                  ),
                ),
              const PopoverSeparator(),
              const PopoverSection('Tags'),
              for (final tag in c.tags)
                PopoverRow(
                  onTap: () => c.toggleTag(tag.label),
                  child: Row(
                    children: [
                      CheckMark(checked: c.query.tags.contains(tag.label)),
                      const SizedBox(width: 8),
                      MiniTag(tag),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SortDropdown extends StatelessWidget {
  const _SortDropdown();

  static const _options = <TranscriptionSort, (String option, String button)>{
    TranscriptionSort.newest: ('Newest first', 'Sort'),
    TranscriptionSort.oldest: ('Oldest first', 'Oldest first'),
    TranscriptionSort.longest: ('Longest', 'Longest'),
    TranscriptionSort.name: ('Name A→Z', 'A→Z'),
  };

  @override
  Widget build(BuildContext context) {
    final sort = context.select<TranscriptionListController, TranscriptionSort>((c) => c.query.sort);
    return Popover(
      minWidth: 200,
      // Deviation from the design: its panel is left-aligned and runs off the
      // right edge of the window, because Sort is the rightmost control.
      alignEnd: true,
      targetBuilder: (context, open, toggle) => _ToolbarButton(
        icon: Icons.sort_rounded,
        label: _options[sort]!.$2,
        active: sort != TranscriptionSort.newest,
        onTap: toggle,
      ),
      panelBuilder: (context, close) => Consumer<TranscriptionListController>(
        builder: (context, c, _) => PopoverPanel(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final entry in _options.entries)
                PopoverRow(
                  onTap: () => c.setSort(entry.key),
                  child: Row(
                    children: [
                      RadioMark(selected: c.query.sort == entry.key),
                      const SizedBox(width: 8),
                      Text(entry.value.$1),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
