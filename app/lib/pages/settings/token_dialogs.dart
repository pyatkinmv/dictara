import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../data/gateway_client.dart';
import '../../data/mcp_tokens_api.dart';
import '../../state/mcp_tokens_controller.dart';
import '../../theme/app_palette.dart';
import '../../widgets/modal.dart';
import 'copy_button.dart';

/// Where the MCP server is: the page's own origin under `/api` (nginx forwards it to the gateway in production, the
/// dev server's proxy does the same locally). Outside a browser, where there is no such origin, the real site.
String mcpServerUrl() {
  final base = Uri.base;
  if (base.scheme != 'http' && base.scheme != 'https') return 'https://dictary.app/api/mcp';
  return '${base.scheme}://${base.authority}/api/mcp';
}

/// The command that connects Claude Code to the server with a token.
String claudeCodeCommand(String url, String secret) =>
    'claude mcp add --transport http dictara $url --header "Authorization: Bearer $secret"';

/// The entry for `opencode.json` that connects OpenCode (a remote server with a bearer header; OAuth is turned off, as
/// the server does not offer it).
String opencodeConfig(String url, String secret) => '''{
  "mcp": {
    "dictara": {
      "type": "remote",
      "url": "$url",
      "enabled": true,
      "oauth": false,
      "headers": { "Authorization": "Bearer $secret" }
    }
  }
}''';

/// Creates a token: first its name and what it may do, then the token itself, shown once with how to connect an agent.
/// It cannot be closed by a click outside, so that the token is not lost by accident. [initialName] and
/// [initialAccess] fill the form for a replacement of an expired token.
Future<void> showCreateTokenDialog(BuildContext context, {String initialName = '', McpAccess initialAccess = McpAccess.read}) {
  return showAppModal<void>(
    context,
    dismissible: false,
    builder: (context) => _CreateTokenDialog(initialName: initialName, initialAccess: initialAccess),
  );
}

class _CreateTokenDialog extends StatefulWidget {
  const _CreateTokenDialog({required this.initialName, required this.initialAccess});

  final String initialName;
  final McpAccess initialAccess;

  @override
  State<_CreateTokenDialog> createState() => _CreateTokenDialogState();
}

class _CreateTokenDialogState extends State<_CreateTokenDialog> {
  late final _name = TextEditingController(text: widget.initialName);
  late McpAccess _access = widget.initialAccess;
  CreatedMcpToken? _created;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _create() async {
    final name = _name.text.trim();
    if (name.isEmpty || _busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final created = await context.read<McpTokensController>().create(name: name, access: _access);
      if (mounted) setState(() => _created = created);
    } on GatewayException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final created = _created;
    return ModalCard(
      width: 560,
      radius: 18,
      shadow: const BoxShadow(color: Color(0x1F000000), blurRadius: 48, offset: Offset(0, 16)),
      child: created == null ? _form(p) : _shown(p, created),
    );
  }

  Widget _form(AppPalette p) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ModalHeader(title: 'Create token', onClose: () => Navigator.of(context).pop()),
        _Caption('Name'),
        const SizedBox(height: 6),
        TextField(
          key: const Key('token-name'),
          controller: _name,
          autofocus: true,
          cursorColor: p.accent,
          onChanged: (_) => setState(() => _error = null),
          onSubmitted: (_) => _create(),
          style: TextStyle(fontSize: 14, color: p.text),
          decoration: InputDecoration(
            isDense: true,
            filled: true,
            fillColor: p.background,
            hintText: 'e.g. OpenCode on laptop',
            hintStyle: TextStyle(fontSize: 14, color: p.text3),
            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(9), borderSide: BorderSide(color: p.border)),
            enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(9), borderSide: BorderSide(color: p.border)),
            focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(9), borderSide: BorderSide(color: p.accent)),
          ),
        ),
        const SizedBox(height: 16),
        _Caption('Access'),
        const SizedBox(height: 6),
        Row(
          children: [
            for (final access in McpAccess.values) ...[
              _AccessOption(access: access, selected: _access == access, onTap: () => setState(() => _access = access)),
              const SizedBox(width: 8),
            ],
          ],
        ),
        const SizedBox(height: 6),
        Text(
          _access == McpAccess.read
              ? 'The agent can search and read your transcripts, tags and speakers.'
              : 'The agent can also rename transcripts and change tags and speakers (and delete them). It never starts paid work such as new summaries.',
          style: TextStyle(fontSize: 12, height: 1.45, color: p.text3),
        ),
        const SizedBox(height: 14),
        Text(
          'The token works for 90 days. Before it ends it is marked on this page, and you can create a new one.',
          style: TextStyle(fontSize: 12, height: 1.45, color: p.text3),
        ),
        if (_error != null) ...[
          const SizedBox(height: 12),
          Text(_error!, key: const Key('token-error'), style: TextStyle(fontSize: 12.5, color: p.danger, height: 1.45)),
        ],
        const SizedBox(height: 20),
        ModalFooter(
          buttons: [
            ModalButton(label: 'Cancel', kind: ModalButtonKind.cancel, onPressed: () => Navigator.of(context).pop()),
            ModalButton(label: _busy ? 'Creating…' : 'Create token', onPressed: _create),
          ],
        ),
      ],
    );
  }

  Widget _shown(AppPalette p, CreatedMcpToken created) {
    final url = mcpServerUrl();
    final secret = created.secret;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const ModalHeader(title: 'Copy your token now'),
        Text(
          'It will not be shown again. If you lose it, create a new token and revoke this one.',
          style: TextStyle(fontSize: 13, height: 1.5, color: p.text2),
        ),
        const SizedBox(height: 12),
        CopyBlock(text: secret, blockKey: const Key('token-secret')),
        const SizedBox(height: 18),
        _Caption('Connect Claude Code'),
        const SizedBox(height: 6),
        CopyBlock(text: claudeCodeCommand(url, secret), multiline: true, copyLabel: 'Copy command', blockKey: const Key('token-claude')),
        const SizedBox(height: 14),
        _Caption('Connect OpenCode (opencode.json)'),
        const SizedBox(height: 6),
        CopyBlock(text: opencodeConfig(url, secret), multiline: true, copyLabel: 'Copy config', blockKey: const Key('token-opencode')),
        const SizedBox(height: 20),
        ModalFooter(buttons: [ModalButton(label: 'I have saved it', onPressed: () => Navigator.of(context).pop())]),
      ],
    );
  }
}

class _Caption extends StatelessWidget {
  const _Caption(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Text(
      text.toUpperCase(),
      style: TextStyle(fontSize: 11, height: 14 / 11, fontWeight: FontWeight.w700, letterSpacing: .77, color: p.text3),
    );
  }
}

class _AccessOption extends StatelessWidget {
  const _AccessOption({required this.access, required this.selected, required this.onTap});

  final McpAccess access;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: Container(
          key: Key('access-${access.code}'),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          decoration: BoxDecoration(
            color: selected ? p.accentLight : p.surface,
            borderRadius: BorderRadius.circular(9),
            border: Border.all(color: selected ? p.accent : p.border),
          ),
          child: Text(
            access.label,
            style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: selected ? p.accent : p.text2),
          ),
        ),
      ),
    );
  }
}

/// "Revoke "OpenCode on laptop"? It stops working immediately." Returns true if the user confirmed.
Future<bool> showRevokeTokenDialog(BuildContext context, {required String name}) async {
  final confirmed = await showAppModal<bool>(context, builder: (context) => _RevokeDialog(name: name));
  return confirmed ?? false;
}

class _RevokeDialog extends StatelessWidget {
  const _RevokeDialog({required this.name});

  final String name;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return ModalCard(
      width: 380,
      radius: 16,
      shadow: const BoxShadow(color: Color(0x26000000), blurRadius: 40, offset: Offset(0, 12)),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Align(
            alignment: Alignment.centerLeft,
            child: Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(color: p.dangerBg, shape: BoxShape.circle),
              child: Icon(Icons.key_off_rounded, size: 20, color: p.danger),
            ),
          ),
          const SizedBox(height: 14),
          Text('Revoke "$name"?', style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
          const SizedBox(height: 6),
          Text(
            'It stops working immediately. Agents using it will no longer be able to read your transcripts.',
            style: TextStyle(fontSize: 14, height: 1.55, color: p.text2),
          ),
          const SizedBox(height: 20),
          ModalFooter(
            buttons: [
              ModalButton(label: 'Cancel', kind: ModalButtonKind.cancel, onPressed: () => Navigator.of(context).pop(false)),
              ModalButton(label: 'Revoke', kind: ModalButtonKind.danger, onPressed: () => Navigator.of(context).pop(true)),
            ],
          ),
        ],
      ),
    );
  }
}
