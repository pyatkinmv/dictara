import 'package:flutter/foundation.dart';

import '../data/gateway_client.dart';
import '../data/mcp_tokens_api.dart';
import 'auth_controller.dart';

/// The user's MCP tokens for the Settings page. Read again each time the page opens, and after every change; cleared
/// when the user signs out. A failed read is not shown as an error: the list just stays as it was.
class McpTokensController extends ChangeNotifier {
  McpTokensController(this._api, this._auth) {
    _auth.addListener(_onAuthChanged);
  }

  final McpTokensApi _api;
  final AuthController _auth;
  bool _disposed = false;

  List<McpToken> _tokens = const [];
  bool _loaded = false;

  /// Newest first, ended ones included (the page shows them as expired).
  List<McpToken> get tokens => _tokens;

  /// False until the list has been read once.
  bool get loaded => _loaded;

  int get expiredCount => _tokens.where((t) => t.status == McpTokenStatus.expired).length;
  int get expiringCount => _tokens.where((t) => t.status == McpTokenStatus.expiring).length;

  /// Something the user should look at: a token that has ended or is about to.
  bool get needsAttention => expiredCount + expiringCount > 0;

  void _onAuthChanged() {
    if (!_auth.isLoggedIn && (_tokens.isNotEmpty || _loaded)) {
      _tokens = const [];
      _loaded = false;
      notifyListeners();
    }
  }

  Future<void> load() async {
    try {
      final tokens = await _api.list();
      if (_disposed) return;
      _tokens = tokens;
      _loaded = true;
      notifyListeners();
    } on GatewayException {
      // Left as it was; the next visit to the page tries again.
    }
  }

  /// Makes a token and returns it with its secret, the one time it is known. A refusal is thrown as it came, for the
  /// dialog to show.
  Future<CreatedMcpToken> create({required String name, required McpAccess access}) async {
    final created = await _api.create(name: name, access: access);
    await load();
    return created;
  }

  Future<void> revoke(String id) async {
    await _api.revoke(id);
    await load();
  }

  @override
  void dispose() {
    _disposed = true;
    _auth.removeListener(_onAuthChanged);
    super.dispose();
  }
}
