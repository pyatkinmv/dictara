import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'package:dictara_web/data/gateway_client.dart';
import 'package:dictara_web/data/profile_api.dart';
import 'package:dictara_web/services/token_store.dart';
import 'package:dictara_web/state/auth_controller.dart';
import 'package:dictara_web/state/profile_controller.dart';

import 'support/fake_auth_api.dart';
import 'support/fake_profile_api.dart';
import 'support/fake_transcription_repository.dart';
import 'support/in_memory_token_store.dart';
import 'test_utils.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('ProfileController', () {
    test('loads on construction when a session already exists', () async {
      final api = FakeProfileApi();
      final auth = AuthController(FakeAuthApi(), InMemoryTokenStore(const StoredToken(token: 't', displayName: 'Maxim')));

      final profile = ProfileController(api, auth);
      await Future<void>.delayed(Duration.zero); // the load is async even though the session was already there

      expect(api.calls, 1);
      expect(profile.profile?.planName, 'Free');
      expect(profile.profile?.usage.used, 3);
      expect(profile.profile?.usage.max, 10);
      profile.dispose();
    });

    test('does not fetch while signed out, then loads once a login lands', () async {
      final api = FakeProfileApi();
      final authApi = FakeAuthApi()..confirm(jwt: 'jwt', displayName: 'Maxim');
      final auth = AuthController(authApi, InMemoryTokenStore(), pollInterval: const Duration(milliseconds: 5));
      final profile = ProfileController(api, auth);

      expect(api.calls, 0);

      await auth.submitUsername('maxim');
      await Future<void>.delayed(const Duration(milliseconds: 40));

      expect(api.calls, 1);
      expect(profile.profile, isNotNull);
      profile.dispose();
      auth.dispose();
    });

    test('logout clears the profile without a new request', () async {
      final api = FakeProfileApi();
      final auth = AuthController(FakeAuthApi(), InMemoryTokenStore(const StoredToken(token: 't', displayName: 'Maxim')));
      final profile = ProfileController(api, auth);
      await Future<void>.delayed(Duration.zero);
      expect(profile.profile, isNotNull);

      auth.logout();

      expect(profile.profile, isNull);
      expect(api.calls, 1); // logging out does not itself ask the server anything
      profile.dispose();
    });

    test('a refusal is swallowed: the profile is simply left as it was', () async {
      final api = FakeProfileApi()..failWith = const GatewayException('down');
      final auth = AuthController(FakeAuthApi(), InMemoryTokenStore(const StoredToken(token: 't', displayName: 'Maxim')));

      final profile = ProfileController(api, auth);
      await Future<void>.delayed(Duration.zero);

      expect(profile.profile, isNull);
      profile.dispose();
    });
  });

  group('Sidebar usage panel', () {
    Future<AuthController> pumpWith(WidgetTester tester, UserProfile userProfile) async {
      useDesktopSize(tester);
      final auth = signedInAuthController();
      final api = FakeProfileApi()..onMe = () => userProfile;
      final profile = ProfileController(api, auth);
      addTearDown(profile.dispose);
      await tester.pumpWidget(DictaraApp(
        authController: auth,
        profileController: profile,
        repository: FakeTranscriptionRepository(),
      ));
      await settle(tester);
      return auth;
    }

    testWidgets('a capped plan shows the badge, the bar and a denominator', (tester) async {
      await pumpWith(tester, const UserProfile(planName: 'Free', usage: UsageInfo(used: 3, max: 10, unit: 'transcriptions')));

      expect(find.text('Free'), findsOneWidget);
      expect(find.text('3 / 10 transcriptions'), findsOneWidget);
      expect(find.text('Upgrade'), findsOneWidget);
    });

    testWidgets('an unlimited plan shows a count with no denominator, no bar and no Upgrade', (tester) async {
      await pumpWith(tester, const UserProfile(planName: 'Unlimited', usage: UsageInfo(used: 14, unit: 'transcriptions')));

      expect(find.text('Unlimited'), findsOneWidget);
      expect(find.text('14 transcriptions this month'), findsOneWidget);
      expect(find.text('Upgrade'), findsNothing);
    });

    testWidgets('nothing is shown before the profile has loaded', (tester) async {
      useDesktopSize(tester);
      final auth = signedInAuthController();
      // NoopProfileApi (the default when no ProfileController is given) never resolves.
      await tester.pumpWidget(DictaraApp(authController: auth, repository: FakeTranscriptionRepository()));
      await settle(tester);

      expect(find.text('Usage this month'), findsNothing);
    });
  });

  group('ProfileMenu with real data', () {
    testWidgets('shows the signed-in name and plan, and no email', (tester) async {
      useDesktopSize(tester);
      final auth = signedInAuthController(displayName: 'Maxim P.');
      final api = FakeProfileApi()..onMe = () => const UserProfile(planName: 'Free', usage: UsageInfo(used: 1, max: 10, unit: 'transcriptions'));
      final profile = ProfileController(api, auth);
      addTearDown(profile.dispose);
      await tester.pumpWidget(DictaraApp(authController: auth, profileController: profile, repository: FakeTranscriptionRepository()));
      await settle(tester);

      expect(find.text('MP'), findsOneWidget); // the avatar initials
      expect(find.text('Maxim P.'), findsWidgets); // sidebar row + (once opened) menu head

      await tester.tap(find.text('Maxim P.').first);
      await settle(tester);

      expect(find.text('Free plan'), findsOneWidget); // under the name in the row
      expect(find.text('Free'), findsWidgets); // the "Manage plan" badge (and the usage-panel badge)
      expect(find.textContaining('@'), findsNothing); // no email anywhere — Telegram never gave one
    });

    testWidgets('a Telegram user with no name known shows its "@" fallback, but not as the avatar initial', (tester) async {
      useDesktopSize(tester);
      final auth = signedInAuthController(displayName: '@sidebartest');
      await tester.pumpWidget(DictaraApp(authController: auth, repository: FakeTranscriptionRepository()));
      await settle(tester);

      expect(find.text('@sidebartest'), findsOneWidget);
      expect(find.text('S'), findsOneWidget); // not "@"
    });
  });
}
