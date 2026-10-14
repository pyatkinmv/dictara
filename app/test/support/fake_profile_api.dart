import 'package:dictara_web/data/profile_api.dart';

/// A scripted [ProfileApi] for tests: no server, the test decides what `me()` answers.
class FakeProfileApi implements ProfileApi {
  UserProfile Function()? onMe;

  /// Thrown by the next call, once.
  Object? failWith;

  int calls = 0;

  @override
  Future<UserProfile> me() async {
    calls++;
    final fail = failWith;
    if (fail != null) {
      failWith = null;
      throw fail;
    }
    return onMe?.call() ?? const UserProfile(planName: 'Free', usage: UsageInfo(used: 3, max: 10, unit: 'transcriptions'));
  }
}
