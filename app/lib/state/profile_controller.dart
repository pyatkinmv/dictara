import 'package:flutter/foundation.dart';

import '../data/gateway_client.dart';
import '../data/profile_api.dart';
import 'auth_controller.dart';

/// The signed-in user's own plan and usage, for the sidebar (`docs/local-backend-integration.md`, "Итерация
/// 11"). Loads once a session exists and follows [AuthController]: a fresh login reloads it, a logout clears
/// it. A refusal is not shown to the user — this is a secondary panel, not worth a snack bar — the panel just
/// stays blank until the next successful load.
class ProfileController extends ChangeNotifier {
  ProfileController(this._api, this._auth) {
    _auth.addListener(_onAuthChanged);
    if (_auth.isLoggedIn) _load();
  }

  final ProfileApi _api;
  final AuthController _auth;
  bool _disposed = false;

  UserProfile? get profile => _profile;
  UserProfile? _profile;

  void _onAuthChanged() {
    if (_auth.isLoggedIn) {
      _load();
    } else if (_profile != null) {
      _profile = null;
      notifyListeners();
    }
  }

  Future<void> _load() async {
    try {
      final profile = await _api.me();
      if (_disposed) return;
      _profile = profile;
      notifyListeners();
    } on GatewayException {
      // Left as it was; _onAuthChanged() will try again on the next login.
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _auth.removeListener(_onAuthChanged);
    super.dispose();
  }
}
