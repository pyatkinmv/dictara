import 'dart:async';

import 'package:flutter/foundation.dart';

import '../data/auth_api.dart';
import '../data/gateway_client.dart';
import '../services/token_store.dart';

/// Which screen of the login card is showing.
enum LoginStage { input, notified, unknownUser }

/// Drives the login screen and holds the signed-in session (`docs/local-backend-integration.md`, "Итерация
/// 10"). `main_material.dart` gives it the real [AuthApi] and [TokenStore] and wires [onToken] to the
/// [GatewayClient] the rest of the app shares, so every request after a login carries the bearer token; widget
/// tests give it fakes (`test/support/`) and usually leave [onToken] out.
///
/// A stored session (from a previous visit) is picked up synchronously in the constructor, so nothing flashes
/// the login screen before immediately signing back in.
class AuthController extends ChangeNotifier {
  AuthController(
    this._api,
    this._tokenStore, {
    this.onToken,
    Duration pollInterval = const Duration(seconds: 3),
  }) : _pollInterval = pollInterval {
    final stored = _tokenStore.read();
    if (stored != null) _applySession(stored.token, stored.displayName, persist: false);
  }

  final AuthApi _api;
  final TokenStore _tokenStore;
  final Duration _pollInterval;

  /// Told about every change of the bearer token (a login, a logout); null means "no longer signed in".
  final void Function(String? token)? onToken;

  String? _token;
  String? _displayName;
  bool _disposed = false;

  bool get isLoggedIn => _token != null;
  String? get displayName => _displayName;

  LoginStage _stage = LoginStage.input;
  LoginStage get stage => _stage;

  bool _busy = false;
  bool get busy => _busy;

  /// What went wrong with the last [submitUsername], if anything; cleared by the next attempt.
  String? get error => _error;
  String? _error;

  /// Where to open the bot, once the "not found" stage is showing.
  String? get botUrl => _botUrl;
  String? _botUrl;

  /// The username the current attempt (or the last one) was for, as typed.
  String get username => _username;
  String _username = '';

  String? _pollToken;
  Timer? _pollTimer;

  /// Submits the username from the input stage: `@username` or `username` alike. Moves to "Check Telegram" or
  /// "not found" once the gateway answers; a refusal (network, server) is left in [error] and the stage does
  /// not change, so the field and the message stay on screen together.
  Future<void> submitUsername(String raw) async {
    final username = raw.trim().replaceFirst(RegExp(r'^@'), '');
    if (username.isEmpty || _busy) return;
    _username = username;
    _busy = true;
    _error = null;
    notifyListeners();
    try {
      final attempt = await _api.loginByUsername(username);
      if (_disposed) return;
      _pollToken = attempt.token;
      _botUrl = attempt.botUrl;
      _stage = attempt.notified ? LoginStage.notified : LoginStage.unknownUser;
      _busy = false;
      notifyListeners();
      // Polling continues on "not found" too: the same token resolves if the person starts the bot and
      // confirms afterwards, so the screen quietly moves on the moment that happens.
      _schedulePoll();
    } on GatewayException catch (e) {
      if (_disposed) return;
      _busy = false;
      _error = e.message;
      notifyListeners();
    }
  }

  /// "Continue without an account": a brand new disposable session on the trial quota, no confirmation step.
  Future<void> continueAnonymously() async {
    if (_busy) return;
    _busy = true;
    _error = null;
    notifyListeners();
    try {
      final result = await _api.loginAnonymously();
      if (_disposed) return;
      _busy = false;
      _applySession(result.jwt, result.displayName, persist: true);
    } on GatewayException catch (e) {
      if (_disposed) return;
      _busy = false;
      _error = e.message;
      notifyListeners();
    }
  }

  /// From "Check Telegram" or "not found", back to entering a username.
  void reset() {
    _pollTimer?.cancel();
    _pollToken = null;
    _stage = LoginStage.input;
    _error = null;
    notifyListeners();
  }

  void _schedulePoll() {
    _pollTimer?.cancel();
    _pollTimer = Timer(_pollInterval, _pollOnce);
  }

  Future<void> _pollOnce() async {
    final token = _pollToken;
    if (token == null || _disposed) return;
    try {
      final result = await _api.pollLogin(token);
      if (_disposed || _pollToken != token) return; // a reset() or a new attempt overtook this one
      if (result == null) {
        _schedulePoll();
      } else {
        _pollToken = null;
        _applySession(result.jwt, result.displayName, persist: true);
      }
    } on GatewayException {
      // A dropped poll is not fatal: the server hiccuped or the network blinked. Try again at the next tick
      // rather than stranding the user on "Check Telegram" forever.
      if (!_disposed && _pollToken == token) _schedulePoll();
    }
  }

  void _applySession(String token, String? displayName, {required bool persist}) {
    _token = token;
    _displayName = displayName;
    _stage = LoginStage.input;
    onToken?.call(token);
    if (persist) _tokenStore.write(StoredToken(token: token, displayName: displayName));
    notifyListeners();
  }

  /// Also what the gateway calls when a request comes back 401: the session on hand is no longer accepted.
  void logout() {
    if (_token == null) return;
    _pollTimer?.cancel();
    _pollToken = null;
    _token = null;
    _displayName = null;
    _stage = LoginStage.input;
    onToken?.call(null);
    _tokenStore.clear();
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _pollTimer?.cancel();
    super.dispose();
  }
}
