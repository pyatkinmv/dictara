import 'gateway_client.dart';

/// How much of a plan's monthly allowance has been used. [max] is null for a plan with no cap — shown as a
/// count with no denominator and no progress bar.
class UsageInfo {
  const UsageInfo({required this.used, this.max, required this.unit});

  final int used;
  final int? max;
  final String unit;
}

class UserProfile {
  const UserProfile({required this.planName, required this.usage});

  final String planName;
  final UsageInfo usage;
}

/// `GET /users/me` (`docs/api-gaps.md` §2.6): the signed-in user's own plan and usage, for the sidebar. The
/// display name itself is not here — it already comes from the login (`AuthController.displayName`).
abstract class ProfileApi {
  Future<UserProfile> me();
}

class HttpProfileApi implements ProfileApi {
  HttpProfileApi(this._client);

  final GatewayClient _client;

  @override
  Future<UserProfile> me() async {
    final body = await _client.getJson('/users/me');
    if (body is! Map<String, dynamic> || body['plan_name'] is! String || body['usage'] is! Map) {
      throw const GatewayException('Expected a user profile');
    }
    final usage = body['usage'] as Map<String, dynamic>;
    if (usage['used'] is! num || usage['unit'] is! String) throw const GatewayException('Expected a user profile');
    return UserProfile(
      planName: body['plan_name'] as String,
      usage: UsageInfo(
        used: (usage['used'] as num).toInt(),
        max: (usage['max'] as num?)?.toInt(),
        unit: usage['unit'] as String,
      ),
    );
  }
}

/// The default when nobody gave `DictaraApp` a real [ProfileApi] (tests that are not about the profile panel):
/// never answers, so the panel simply stays blank rather than showing anything made up.
class NoopProfileApi implements ProfileApi {
  const NoopProfileApi();

  @override
  Future<UserProfile> me() => Future.error(const GatewayException('No profile API configured'));
}
