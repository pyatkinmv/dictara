import 'gateway_client.dart';

/// What the gateway answered `POST /auth/login-by-username`.
class LoginAttempt {
  const LoginAttempt({required this.token, required this.notified, this.botUrl});

  /// The polling token (not a JWT — that comes back from [AuthApi.pollLogin] once confirmed).
  final String token;

  /// True: the bot has already been told to ask for confirmation ("Check your Telegram"). False: this
  /// Telegram username has not started the bot yet ("we couldn't find you").
  final bool notified;

  /// Where to open the bot; only set when [notified] is false.
  final String? botUrl;
}

/// What `GET /auth/login-link/{token}` answers once the user has tapped Confirm.
class LoginResult {
  const LoginResult({required this.jwt, this.displayName});

  final String jwt;
  final String? displayName;
}

/// The gateway's Telegram login (`docs/local-backend-integration.md`, "Итерация 10"). An interface so the
/// login screen can be driven in widget tests without a server.
abstract class AuthApi {
  Future<LoginAttempt> loginByUsername(String username);

  /// Null while the user has not confirmed yet; the caller polls again.
  Future<LoginResult?> pollLogin(String pollToken);

  /// "Continue without an account": a brand new, disposable session, on a small fixed trial quota — nothing
  /// to confirm, so unlike [loginByUsername] this succeeds right away with no polling.
  Future<LoginResult> loginAnonymously();
}

class HttpAuthApi implements AuthApi {
  HttpAuthApi(this._client);

  final GatewayClient _client;

  @override
  Future<LoginAttempt> loginByUsername(String username) async {
    final body = await _client.postJson('/auth/login-by-username', {'telegram_username': username});
    if (body is! Map<String, dynamic> || body['token'] is! String) {
      throw const GatewayException('Expected a login attempt');
    }
    return LoginAttempt(
      token: body['token'] as String,
      notified: body['status'] == 'notified',
      botUrl: body['bot_url'] as String?,
    );
  }

  @override
  Future<LoginResult?> pollLogin(String pollToken) async {
    final body = await _client.getJson('/auth/login-link/${Uri.encodeComponent(pollToken)}');
    if (body is! Map<String, dynamic>) throw const GatewayException('Expected a login status');
    if (body['confirmed'] != true) return null;
    final jwt = body['token'] as String?;
    if (jwt == null) throw const GatewayException('Confirmed without a token');
    return LoginResult(jwt: jwt, displayName: body['display_name'] as String?);
  }

  @override
  Future<LoginResult> loginAnonymously() async {
    final body = await _client.postJson('/auth/anonymous', {});
    if (body is! Map<String, dynamic>) throw const GatewayException('Expected a login result');
    final jwt = body['token'] as String?;
    if (jwt == null) throw const GatewayException('Anonymous login without a token');
    return LoginResult(jwt: jwt, displayName: body['display_name'] as String?);
  }
}
