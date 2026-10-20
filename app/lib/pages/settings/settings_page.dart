import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../shell/breakpoints.dart';
import '../../state/mcp_tokens_controller.dart';
import '../../theme/app_palette.dart';
import 'mcp_access_tab.dart';
import 'profile_tab.dart';

/// One tab of the Settings page. The sections are listed in one place ([settingsSections]): adding a section is one
/// more entry here and a page for it; the tabs, the routes and the default come from this list.
class SettingsSection {
  const SettingsSection({required this.path, required this.label, required this.builder});

  /// The last part of the address: `/settings/<path>`.
  final String path;
  final String label;
  final WidgetBuilder builder;
}

/// In tab order. The first is the one `/settings` opens.
final settingsSections = <SettingsSection>[
  SettingsSection(path: 'mcp-access', label: 'MCP access', builder: (_) => const McpAccessTab()),
  SettingsSection(path: 'profile', label: 'Profile', builder: (_) => const ProfileTab()),
];

String get defaultSettingsPath => '/settings/${settingsSections.first.path}';

/// Settings: a title, the tabs, and the chosen section under them (`/settings/<section>`).
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.section});

  /// The section in the address; one that does not exist falls back to the first.
  final String? section;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  @override
  void initState() {
    super.initState();
    // Whichever tab is open, the tokens are read, so that a token that has ended is marked on its tab at once.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<McpTokensController>().load();
    });
  }

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final current = settingsSections.firstWhere((s) => s.path == widget.section, orElse: () => settingsSections.first);
    final mobile = isMobileWidth(context);
    final tokens = context.watch<McpTokensController>();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          padding: EdgeInsets.fromLTRB(mobile ? 16 : 28, 20, mobile ? 16 : 28, 0),
          decoration: BoxDecoration(border: Border(bottom: BorderSide(color: p.border))),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Settings', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, letterSpacing: -.4)),
              const SizedBox(height: 12),
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  children: [
                    for (final s in settingsSections)
                      _Tab(
                        label: s.label,
                        selected: s.path == current.path,
                        // A red dot for a token that has ended, an amber one for one that is about to.
                        dot: s.path == 'mcp-access' && tokens.needsAttention
                            ? (tokens.expiredCount > 0 ? p.danger : p.warn)
                            : null,
                        onTap: () => context.go('/settings/${s.path}'),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
        Expanded(
          child: SingleChildScrollView(
            padding: EdgeInsets.symmetric(horizontal: mobile ? 16 : 28, vertical: 20),
            child: Align(
              alignment: Alignment.topLeft,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 760),
                child: KeyedSubtree(key: ValueKey(current.path), child: current.builder(context)),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _Tab extends StatefulWidget {
  const _Tab({required this.label, required this.selected, required this.onTap, this.dot});

  final String label;
  final bool selected;
  final VoidCallback onTap;
  final Color? dot;

  @override
  State<_Tab> createState() => _TabState();
}

class _TabState extends State<_Tab> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final color = widget.selected ? p.accent : (_hover ? p.text2 : p.text3);
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        behavior: HitTestBehavior.opaque,
        child: Container(
          padding: const EdgeInsets.fromLTRB(2, 6, 2, 10),
          margin: const EdgeInsets.only(right: 20),
          decoration: BoxDecoration(
            border: Border(bottom: BorderSide(color: widget.selected ? p.accent : Colors.transparent, width: 2)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                widget.label,
                style: TextStyle(fontSize: 13.5, fontWeight: widget.selected ? FontWeight.w700 : FontWeight.w500, color: color),
              ),
              if (widget.dot != null) ...[
                const SizedBox(width: 6),
                Container(
                  key: const Key('settings-tab-dot'),
                  width: 7,
                  height: 7,
                  decoration: BoxDecoration(color: widget.dot, shape: BoxShape.circle),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
