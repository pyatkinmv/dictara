import 'gateway_client.dart';

/// What a token may do (`access` of the gateway's `/mcp-tokens`).
enum McpAccess {
  read('read', 'Read only'),
  write('write', 'Read and write');

  const McpAccess(this.code, this.label);

  final String code;
  final String label;

  static McpAccess of(String? code) => values.firstWhere((a) => a.code == code, orElse: () => read);
}

/// Where a token stands, as the gateway works it out: working, working but ending within 14 days, or ended.
enum McpTokenStatus { active, expiring, expired }

/// A personal access token of the user, as listed. The secret is not here: it exists only in [CreatedMcpToken].
class McpToken {
  const McpToken({
    required this.id,
    required this.name,
    required this.prefix,
    required this.access,
    required this.status,
    required this.createdAt,
    required this.expiresAt,
    this.lastUsedAt,
  });

  final String id;
  final String name;

  /// The first characters of the secret, to tell tokens apart (`dtr_Zk3p`).
  final String prefix;
  final McpAccess access;
  final McpTokenStatus status;
  final DateTime createdAt;
  final DateTime expiresAt;

  /// Null for a token that has never been used.
  final DateTime? lastUsedAt;
}

/// A token just created: the one time its secret is shown.
class CreatedMcpToken {
  const CreatedMcpToken({required this.token, required this.secret});

  final McpToken token;
  final String secret;
}

/// The user's MCP tokens (`/mcp-tokens` of the gateway, `docs/mcp.md`): list, create, revoke.
abstract class McpTokensApi {
  Future<List<McpToken>> list();

  /// Throws a [GatewayException] with the gateway's own message for a refusal (no name, too many tokens).
  Future<CreatedMcpToken> create({required String name, required McpAccess access});

  /// Revokes the token, or removes an ended one from the list; either way it stops working.
  Future<void> revoke(String id);
}

class HttpMcpTokensApi implements McpTokensApi {
  HttpMcpTokensApi(this._client);

  final GatewayClient _client;

  @override
  Future<List<McpToken>> list() async {
    final body = await _client.getJson('/mcp-tokens');
    if (body is! Map<String, dynamic> || body['tokens'] is! List) throw const GatewayException('Expected the list of tokens');
    return [for (final entry in body['tokens'] as List<dynamic>) _tokenFromJson(entry as Map<String, dynamic>)];
  }

  @override
  Future<CreatedMcpToken> create({required String name, required McpAccess access}) async {
    final body = await _client.postJson('/mcp-tokens', {'name': name, 'access': access.code});
    if (body is! Map<String, dynamic> || body['token'] is! String) throw const GatewayException('Expected the new token');
    return CreatedMcpToken(token: _tokenFromJson(body), secret: body['token'] as String);
  }

  @override
  Future<void> revoke(String id) => _client.deleteJson('/mcp-tokens/${Uri.encodeComponent(id)}');
}

McpToken _tokenFromJson(Map<String, dynamic> json) => McpToken(
  id: json['id'] as String,
  name: json['name'] as String,
  prefix: json['prefix'] as String,
  access: McpAccess.of(json['access'] as String?),
  status: McpTokenStatus.values.firstWhere((s) => s.name == json['status'], orElse: () => McpTokenStatus.active),
  createdAt: DateTime.parse(json['created_at'] as String).toLocal(),
  expiresAt: DateTime.parse(json['expires_at'] as String).toLocal(),
  lastUsedAt: json['last_used_at'] is String ? DateTime.parse(json['last_used_at'] as String).toLocal() : null,
);

/// The default when nobody gave `DictaraApp` a real [McpTokensApi] (tests that are not about tokens): never answers.
class NoopMcpTokensApi implements McpTokensApi {
  const NoopMcpTokensApi();

  @override
  Future<List<McpToken>> list() => Future.error(const GatewayException('No tokens API configured'));

  @override
  Future<CreatedMcpToken> create({required String name, required McpAccess access}) =>
      Future.error(const GatewayException('No tokens API configured'));

  @override
  Future<void> revoke(String id) => Future.error(const GatewayException('No tokens API configured'));
}
