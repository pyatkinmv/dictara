import 'package:dictara_web/data/auth_api.dart';

/// A scripted [AuthApi] for widget and unit tests: no server, no local bot — the test decides what each call
/// answers.
class FakeAuthApi implements AuthApi {
  /// What [loginByUsername] answers; defaults to "notified" so a login can be driven to completion with
  /// [confirm].
  LoginAttempt Function(String username)? onLogin;

  /// What [pollLogin] answers; defaults to null (not confirmed yet) until [confirm] is called.
  LoginResult? Function(String pollToken)? onPoll;

  /// Thrown by the next [loginByUsername] call, once.
  Object? failLoginWith;

  int loginCalls = 0;
  int pollCalls = 0;
  String? lastUsername;
  String? lastPollToken;

  LoginResult? _confirmed;

  /// Makes the next (and every later) [pollLogin] answer as confirmed, the way the local bot stub or a real
  /// tap of Confirm eventually would.
  void confirm({String jwt = 'test-jwt', String? displayName = 'Test User'}) {
    _confirmed = LoginResult(jwt: jwt, displayName: displayName);
  }

  @override
  Future<LoginAttempt> loginByUsername(String username) async {
    loginCalls++;
    lastUsername = username;
    final fail = failLoginWith;
    if (fail != null) {
      failLoginWith = null;
      throw fail;
    }
    return onLogin?.call(username) ?? const LoginAttempt(token: 'poll-token', notified: true);
  }

  @override
  Future<LoginResult?> pollLogin(String pollToken) async {
    pollCalls++;
    lastPollToken = pollToken;
    return onPoll?.call(pollToken) ?? _confirmed;
  }
}
