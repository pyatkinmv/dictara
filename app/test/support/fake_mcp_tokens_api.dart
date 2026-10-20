import 'dart:async';

import 'package:dictara_web/data/mcp_tokens_api.dart';

/// An in-memory [McpTokensApi] that behaves like the gateway: new tokens come first, a created one has a secret that is
/// never listed, a revoked one is gone.
class FakeMcpTokensApi implements McpTokensApi {
  FakeMcpTokensApi([List<McpToken> tokens = const []]) : tokens = [...tokens];

  List<McpToken> tokens;

  /// Whether the user's plan includes MCP access (what the gateway's list says).
  bool available = true;

  /// Thrown by the next `create`, once (a refusal such as "too many tokens").
  Object? failCreateWith;

  /// Thrown by the next `list`, once.
  Object? failListWith;

  /// When set, `list` waits for it: the page is still loading.
  Completer<void>? listGate;

  /// Thrown by the next `revoke`, once.
  Object? failRevokeWith;

  int listCalls = 0;
  final created = <({String name, McpAccess access})>[];
  final revoked = <String>[];
  var _next = 100;

  @override
  Future<McpTokenList> list() async {
    listCalls++;
    await listGate?.future;
    final fail = failListWith;
    if (fail != null) {
      failListWith = null;
      throw fail;
    }
    return McpTokenList(tokens: List.unmodifiable(tokens), available: available);
  }

  @override
  Future<CreatedMcpToken> create({required String name, required McpAccess access}) async {
    final fail = failCreateWith;
    if (fail != null) {
      failCreateWith = null;
      throw fail;
    }
    created.add((name: name, access: access));
    final id = 'new-${_next++}';
    final now = DateTime.now();
    final token = McpToken(
      id: id,
      name: name,
      prefix: 'dtr_New$id'.substring(0, 8),
      access: access,
      status: McpTokenStatus.active,
      createdAt: now,
      expiresAt: now.add(const Duration(days: 90)),
    );
    tokens = [token, ...tokens];
    return CreatedMcpToken(token: token, secret: 'dtr_${'S' * 43}');
  }

  @override
  Future<void> revoke(String id) async {
    final fail = failRevokeWith;
    if (fail != null) {
      failRevokeWith = null;
      throw fail;
    }
    revoked.add(id);
    tokens = [for (final t in tokens) if (t.id != id) t];
  }
}

/// A token for tests: working unless said otherwise.
McpToken fakeToken(
  String name, {
  String? id,
  McpAccess access = McpAccess.read,
  McpTokenStatus status = McpTokenStatus.active,
  Duration? expiresIn,
  DateTime? lastUsedAt,
}) {
  final now = DateTime.now();
  final left = expiresIn ??
      switch (status) {
        McpTokenStatus.active => const Duration(days: 60),
        McpTokenStatus.expiring => const Duration(days: 5, hours: 3),
        McpTokenStatus.expired => const Duration(days: -3),
      };
  return McpToken(
    id: id ?? name.toLowerCase().replaceAll(' ', '-'),
    name: name,
    prefix: 'dtr_Zk3p',
    access: access,
    status: status,
    createdAt: now.subtract(const Duration(days: 30)),
    expiresAt: now.add(left),
    lastUsedAt: lastUsedAt,
  );
}
