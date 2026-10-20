import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../data/mcp_tokens_api.dart';
import '../../shell/breakpoints.dart';
import '../../state/mcp_tokens_controller.dart';
import '../../theme/app_palette.dart';
import '../../util/format.dart';
import 'copy_button.dart';
import 'token_dialogs.dart';

/// The MCP access tab: the server's address, and the user's tokens for AI agents as cards (working, ending soon, ended).
class McpAccessTab extends StatelessWidget {
  const McpAccessTab({super.key});

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final controller = context.watch<McpTokensController>();
    final tokens = controller.tokens;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          'Let AI agents such as Claude Code or OpenCode search and read your transcripts. A token belongs to you only: it '
          'never sees anyone else\'s data, and it can never start paid work such as new summaries.',
          style: TextStyle(fontSize: 13.5, height: 1.55, color: p.text2),
        ),
        const SizedBox(height: 18),
        Text(
          'SERVER ADDRESS',
          style: TextStyle(fontSize: 11, height: 14 / 11, fontWeight: FontWeight.w700, letterSpacing: .77, color: p.text3),
        ),
        const SizedBox(height: 6),
        CopyBlock(text: mcpServerUrl(), blockKey: const Key('mcp-server-url')),
        const SizedBox(height: 22),
        if (controller.expiredCount > 0) ...[
          _ExpiredBanner(count: controller.expiredCount),
          const SizedBox(height: 14),
        ],
        Row(
          children: [
            const Expanded(child: Text('Your tokens', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700))),
            _CreateButton(onTap: () => showCreateTokenDialog(context)),
          ],
        ),
        const SizedBox(height: 10),
        if (!controller.loaded)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 24),
            child: Text('Loading…', style: TextStyle(fontSize: 13, color: p.text3)),
          )
        else if (tokens.isEmpty)
          const _EmptyState()
        else
          for (final token in tokens) ...[
            _TokenCard(token: token),
            const SizedBox(height: 10),
          ],
      ],
    );
  }
}

class _ExpiredBanner extends StatelessWidget {
  const _ExpiredBanner({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      key: const Key('expired-banner'),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: p.dangerBg,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: p.danger.withValues(alpha: .5)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.error_outline_rounded, size: 18, color: p.danger),
          const SizedBox(width: 10),
          Expanded(
            child: Text.rich(
              TextSpan(
                children: [
                  TextSpan(
                    text: count == 1 ? '1 token has expired. ' : '$count tokens have expired. ',
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                  TextSpan(text: count == 1 ? 'Agents using it can no longer read your transcripts.' : 'Agents using them can no longer read your transcripts.'),
                ],
              ),
              style: TextStyle(fontSize: 13, height: 1.5, color: p.danger),
            ),
          ),
        ],
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Container(
      key: const Key('no-tokens'),
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 28),
      decoration: BoxDecoration(
        color: p.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: p.border),
      ),
      child: Column(
        children: [
          Icon(Icons.vpn_key_outlined, size: 28, color: p.text3),
          const SizedBox(height: 10),
          const Text('No tokens yet', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
          const SizedBox(height: 4),
          Text(
            'Create a token to let an AI agent read your transcripts.',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 13, height: 1.5, color: p.text3),
          ),
        ],
      ),
    );
  }
}

/// One token. Working: plain. Ending within 14 days: amber, with the days left. Ended: red, said plainly, with a way to
/// make a replacement and to remove it.
class _TokenCard extends StatelessWidget {
  const _TokenCard({required this.token});

  final McpToken token;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final expired = token.status == McpTokenStatus.expired;
    final expiring = token.status == McpTokenStatus.expiring;
    final (Color fill, Color border) = switch (token.status) {
      McpTokenStatus.expired => (p.dangerBg, p.danger.withValues(alpha: .5)),
      McpTokenStatus.expiring => (p.warnBg, p.warn.withValues(alpha: .6)),
      McpTokenStatus.active => (p.surface, p.border),
    };
    final controller = context.read<McpTokensController>();

    final info = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 6,
          runSpacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text(
              token.name,
              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: expired ? p.text3 : p.text),
            ),
            if (expired)
              _Chip(label: 'Expired ${formatDay(token.expiresAt)}', fill: p.danger, color: Colors.white, bold: true)
            else if (expiring)
              _Chip(label: _expiresIn(token.expiresAt, DateTime.now()), fill: p.warn.withValues(alpha: .18), color: p.warn, bold: true),
            _Chip(
              label: token.access == McpAccess.write ? 'read and write' : 'read',
              fill: token.access == McpAccess.write ? p.warnBg : p.background,
              color: token.access == McpAccess.write ? p.warn : p.text2,
            ),
          ],
        ),
        const SizedBox(height: 4),
        Text(
          [
            '${token.prefix}…',
            'created ${formatDay(token.createdAt)}',
            if (!expired && !expiring) 'expires ${formatDay(token.expiresAt)}',
            _lastUsed(token.lastUsedAt, DateTime.now()),
          ].join(' · '),
          style: TextStyle(fontSize: 12, height: 1.5, color: p.text3),
        ),
        if (expired) ...[
          const SizedBox(height: 4),
          Text(
            'Stopped working. Create a new token and put it in your agent\'s settings.',
            style: TextStyle(fontSize: 12.5, height: 1.5, color: p.danger),
          ),
        ],
      ],
    );

    final buttons = Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        if (expired)
          _SmallButton(
            label: 'Create replacement',
            primary: true,
            onTap: () => showCreateTokenDialog(context, initialName: token.name, initialAccess: token.access),
          ),
        _SmallButton(
          label: expired ? 'Delete' : 'Revoke',
          danger: true,
          onTap: () async {
            // An ended token is already dead: removing it from the list needs no second thought.
            final go = expired || await showRevokeTokenDialog(context, name: token.name);
            if (go) await controller.revoke(token.id);
          },
        ),
      ],
    );

    return Container(
      key: Key('token-${token.id}'),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: fill, borderRadius: BorderRadius.circular(12), border: Border.all(color: border)),
      child: isMobileWidth(context)
          ? Column(crossAxisAlignment: CrossAxisAlignment.start, children: [info, const SizedBox(height: 10), buttons])
          : Row(crossAxisAlignment: CrossAxisAlignment.start, children: [Expanded(child: info), const SizedBox(width: 12), buttons]),
    );
  }
}

/// "Expires in 5 days", "Expires tomorrow", "Expires today".
String _expiresIn(DateTime expiresAt, DateTime now) {
  final days = expiresAt.difference(now).inDays;
  if (days <= 0) return 'Expires today';
  if (days == 1) return 'Expires tomorrow';
  return 'Expires in $days days';
}

/// "never used", "used today", "used yesterday", "used 3 days ago", then the date.
String _lastUsed(DateTime? at, DateTime now) {
  if (at == null) return 'never used';
  final days = DateTime(now.year, now.month, now.day).difference(DateTime(at.year, at.month, at.day)).inDays;
  if (days <= 0) return 'used today';
  if (days == 1) return 'used yesterday';
  if (days < 30) return 'used $days days ago';
  return 'used ${formatDay(at)}';
}

class _Chip extends StatelessWidget {
  const _Chip({required this.label, required this.fill, required this.color, this.bold = false});

  final String label;
  final Color fill;
  final Color color;
  final bool bold;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 1),
      decoration: BoxDecoration(color: fill, borderRadius: BorderRadius.circular(100)),
      child: Text(label, style: TextStyle(fontSize: 11, fontWeight: bold ? FontWeight.w700 : FontWeight.w600, color: color)),
    );
  }
}

class _SmallButton extends StatefulWidget {
  const _SmallButton({required this.label, required this.onTap, this.primary = false, this.danger = false});

  final String label;
  final VoidCallback onTap;
  final bool primary;
  final bool danger;

  @override
  State<_SmallButton> createState() => _SmallButtonState();
}

class _SmallButtonState extends State<_SmallButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final Color fill;
    final Color text;
    final Color? border;
    if (widget.primary) {
      (fill, text, border) = (_hover ? p.accentDark : p.accent, Colors.white, null);
    } else if (widget.danger) {
      (fill, text, border) = (_hover ? p.dangerBg : Colors.transparent, p.danger, p.danger.withValues(alpha: .45));
    } else {
      (fill, text, border) = (_hover ? p.accentLight : Colors.transparent, p.text2, p.border);
    }
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        behavior: HitTestBehavior.opaque,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
          decoration: BoxDecoration(
            color: fill,
            borderRadius: BorderRadius.circular(9),
            border: border == null ? null : Border.all(color: border),
          ),
          child: Text(widget.label, style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: text)),
        ),
      ),
    );
  }
}

class _CreateButton extends StatefulWidget {
  const _CreateButton({required this.onTap});

  final VoidCallback onTap;

  @override
  State<_CreateButton> createState() => _CreateButtonState();
}

class _CreateButtonState extends State<_CreateButton> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        key: const Key('create-token'),
        onTap: widget.onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          decoration: BoxDecoration(color: _hover ? p.accentDark : p.accent, borderRadius: BorderRadius.circular(10)),
          child: const Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.add_rounded, size: 16, color: Colors.white),
              SizedBox(width: 6),
              Text('Create token', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: Colors.white)),
            ],
          ),
        ),
      ),
    );
  }
}
