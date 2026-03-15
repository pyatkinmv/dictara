import 'dart:async';
// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'dart:html' as html;

import 'package:flutter/material.dart';

import 'api_client.dart';

class AuthService extends ChangeNotifier {
  static const _tokenKey = 'dictara_jwt';
  static const _nameKey = 'dictara_display_name';

  String? _token = html.window.localStorage[_tokenKey];
  String? _displayName = html.window.localStorage[_nameKey];

  bool get isLoggedIn => _token != null && _token!.isNotEmpty;
  String? get token => _token;
  String? get displayName => (_displayName?.isNotEmpty ?? false) ? _displayName : null;

  Future<void> triggerLogin(BuildContext context, ApiClient api) async {
    if (!context.mounted) return;

    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => _LoginDialog(
        api: api,
        onConfirmed: (jwt, displayName) {
          _token = jwt;
          _displayName = displayName;
          if (jwt != null) html.window.localStorage[_tokenKey] = jwt;
          html.window.localStorage[_nameKey] = displayName ?? '';
          notifyListeners();
        },
      ),
    );
  }

  void logout() {
    _token = null;
    _displayName = null;
    html.window.localStorage.remove(_tokenKey);
    html.window.localStorage.remove(_nameKey);
    notifyListeners();
  }
}

class _LoginDialog extends StatefulWidget {
  final ApiClient api;
  final void Function(String? jwt, String? displayName) onConfirmed;

  const _LoginDialog({required this.api, required this.onConfirmed});

  @override
  State<_LoginDialog> createState() => _LoginDialogState();
}

class _LoginDialogState extends State<_LoginDialog> {
  final _controller = TextEditingController();
  Timer? _timer;
  String? _pollToken;
  String? _botUrl;
  String? _error;
  bool _loading = false;
  // 'input' | 'notified' | 'unknown_user'
  String _stage = 'input';

  @override
  void dispose() {
    _timer?.cancel();
    _controller.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final username = _controller.text.trim().replaceFirst(RegExp(r'^@'), '');
    if (username.isEmpty) return;
    setState(() { _loading = true; _error = null; });
    try {
      final resp = await widget.api.loginByUsername(username);
      final token = resp['token'] as String;
      final status = resp['status'] as String;
      _pollToken = token;
      _startPolling();
      setState(() {
        _loading = false;
        _stage = status == 'notified' ? 'notified' : 'unknown_user';
        _botUrl = resp['bot_url'] as String?;
      });
    } catch (e) {
      setState(() { _loading = false; _error = e.toString(); });
    }
  }

  void _startPolling() {
    _timer = Timer.periodic(const Duration(seconds: 3), (_) => _poll());
  }

  Future<void> _poll() async {
    if (_pollToken == null) return;
    try {
      final resp = await widget.api.pollLoginLink(_pollToken!);
      if (resp['confirmed'] == true) {
        _timer?.cancel();
        widget.onConfirmed(resp['token'] as String?, resp['display_name'] as String?);
        if (mounted) Navigator.of(context, rootNavigator: true).pop();
      }
    } catch (e) {
      _timer?.cancel();
      final msg = e.toString();
      if (mounted) {
        setState(() {
          _error = msg.contains('rejected') ? 'Login rejected.' : 'Link expired. Please try again.';
          _stage = 'input';
          _pollToken = null;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Login with Telegram'),
      content: SizedBox(
        width: 340,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (_error != null) ...[
              Text(_error!, style: const TextStyle(color: Colors.red)),
              const SizedBox(height: 12),
            ],
            if (_stage == 'input') ...[
              const Text('Enter your Telegram username:'),
              const SizedBox(height: 8),
              TextField(
                controller: _controller,
                decoration: const InputDecoration(
                  hintText: '@username',
                  border: OutlineInputBorder(),
                  isDense: true,
                ),
                autofocus: true,
                onSubmitted: (_) => _submit(),
              ),
              const SizedBox(height: 12),
              ElevatedButton(
                onPressed: _loading ? null : _submit,
                child: _loading
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Text('Continue'),
              ),
            ] else if (_stage == 'notified') ...[
              const Row(
                children: [
                  SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
                  SizedBox(width: 10),
                  Expanded(child: Text('Check your Telegram — tap Confirm there.')),
                ],
              ),
            ] else if (_stage == 'unknown_user') ...[
              const Text("We couldn't reach you directly."),
              const SizedBox(height: 8),
              if (_botUrl != null)
                InkWell(
                  onTap: () => html.window.open(_botUrl!, '_blank'),
                  child: Text(
                    'Open @${_botUrl!.split('/').last} in Telegram →',
                    style: const TextStyle(
                      color: Colors.blue,
                      decoration: TextDecoration.underline,
                    ),
                  ),
                ),
              const SizedBox(height: 8),
              const Text(
                'Send /start to the bot, then tap Confirm in the message it sends you.',
                style: TextStyle(fontSize: 12, color: Colors.black54),
              ),
              const SizedBox(height: 12),
              const Row(
                children: [
                  SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
                  SizedBox(width: 10),
                  Text('Waiting for confirmation…', style: TextStyle(fontSize: 13)),
                ],
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context, rootNavigator: true).pop(),
          child: const Text('Cancel'),
        ),
      ],
    );
  }
}
