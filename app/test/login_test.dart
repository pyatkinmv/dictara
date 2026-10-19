import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'package:dictara_web/data/auth_api.dart';
import 'package:dictara_web/data/gateway_client.dart';
import 'package:dictara_web/data/transcription_repository.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/services/token_store.dart';
import 'package:dictara_web/state/auth_controller.dart';

import 'support/fake_auth_api.dart';
import 'support/fake_transcription_repository.dart';
import 'support/in_memory_token_store.dart';
import 'test_utils.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  /// A fresh, signed-out session with [interval] between polls (short, so tests don't wait 3 real seconds).
  // ChangeNotifierProvider.value never disposes what it did not create, so a test whose AuthController ends
  // with a poll still pending (anything short of a confirm or a reset()) must call `auth.dispose()` itself,
  // or the fake timer trips the test framework's "no pending timers" check.
  (AuthController, FakeAuthApi) freshAuth({Duration interval = const Duration(milliseconds: 20)}) {
    final api = FakeAuthApi();
    return (AuthController(api, InMemoryTokenStore(), pollInterval: interval), api);
  }

  Future<void> pump(WidgetTester tester, AuthController auth) async {
    useDesktopSize(tester);
    await tester.pumpWidget(DictaraApp(authController: auth, repository: FakeTranscriptionRepository()));
    await settle(tester);
  }

  group('LoginOverlay', () {
    testWidgets('blocks the app until signed in, then the real content shows', (tester) async {
      final (auth, api) = freshAuth();
      await pump(tester, auth);

      expect(find.text('Continue with your Telegram account'), findsOneWidget);
      expect(find.text('Telegram username'), findsOneWidget);

      await tester.enterText(find.byKey(const Key('login-username-field')), 'maxim');
      await tester.tap(find.text('Continue with Telegram'));
      await tester.pump(); // the request is in flight
      expect(find.byType(CircularProgressIndicator), findsWidgets); // the button's own spinner, among others

      await settle(tester);
      expect(find.text('Check your Telegram'), findsOneWidget);
      expect(find.textContaining('@maxim'), findsOneWidget);

      api.confirm(displayName: 'Maxim');
      await tester.pump(const Duration(milliseconds: 30));
      await settle(tester);

      expect(find.text('Continue with your Telegram account'), findsNothing);
      expect(find.text('Transcriptions'), findsWidgets);
    });

    testWidgets('a username that has not started the bot shows the "not found" screen and keeps waiting', (tester) async {
      final (auth, api) = freshAuth();
      api.onLogin = (_) => const LoginAttempt(token: 'poll-1', notified: false, botUrl: 'https://t.me/dictara_bot');
      await pump(tester, auth);

      await tester.enterText(find.byKey(const Key('login-username-field')), 'nobody');
      await tester.tap(find.text('Continue with Telegram'));
      await settle(tester);

      expect(find.text("We couldn't find you on Telegram"), findsOneWidget);
      expect(find.text('Open @dictara_bot in Telegram →'), findsOneWidget);
      expect(find.text('Waiting for confirmation…'), findsOneWidget);

      // It really is still polling: confirming now still gets the user in.
      api.confirm();
      await tester.pump(const Duration(milliseconds: 30));
      await settle(tester);
      expect(find.text('Transcriptions'), findsWidgets);
    });

    testWidgets('"a different username" goes back to the input, cancelling the poll', (tester) async {
      final (auth, api) = freshAuth();
      await pump(tester, auth);
      await tester.enterText(find.byKey(const Key('login-username-field')), 'maxim');
      await tester.tap(find.text('Continue with Telegram'));
      await settle(tester);
      expect(find.text('Check your Telegram'), findsOneWidget);

      await tester.tap(find.text('← Use a different username'));
      await settle(tester);

      expect(find.text('Continue with your Telegram account'), findsOneWidget);
      // Confirming the old attempt now must not sign the user in: it was abandoned.
      api.confirm();
      await tester.pump(const Duration(milliseconds: 30));
      await settle(tester);
      expect(find.text('Continue with your Telegram account'), findsOneWidget);
    });

    testWidgets('a refusal is shown under the field and the field is not cleared', (tester) async {
      final (auth, api) = freshAuth();
      api.failLoginWith = const GatewayException('Could not reach the server');
      await pump(tester, auth);

      await tester.enterText(find.byKey(const Key('login-username-field')), 'maxim');
      await tester.tap(find.text('Continue with Telegram'));
      await settle(tester);

      expect(find.text('Could not reach the server'), findsOneWidget);
      expect(find.text('Continue with your Telegram account'), findsOneWidget); // stays on the input stage
      expect(find.text('maxim'), findsOneWidget);
    });

    testWidgets('an empty username does nothing', (tester) async {
      final (auth, api) = freshAuth();
      await pump(tester, auth);

      await tester.tap(find.text('Continue with Telegram'));
      await settle(tester);

      expect(api.loginCalls, 0);
      expect(find.text('Continue with your Telegram account'), findsOneWidget);
    });

    testWidgets('a leading @ is stripped before it is sent', (tester) async {
      final (auth, api) = freshAuth();
      await pump(tester, auth);

      await tester.enterText(find.byKey(const Key('login-username-field')), '@maxim');
      await tester.tap(find.text('Continue with Telegram'));
      await settle(tester);

      expect(api.lastUsername, 'maxim');
      auth.dispose(); // still on "Check Telegram", with a poll pending — done with it before the test ends
    });

    testWidgets('a returning visitor with a saved session sees no login screen at all', (tester) async {
      final auth = signedInAuthController(displayName: 'Maxim P.');
      await pump(tester, auth);

      expect(find.text('Continue with your Telegram account'), findsNothing);
      expect(find.text('Transcriptions'), findsWidgets);
    });

    testWidgets('the list, fetched before anyone was signed in, is fetched again once the login lands', (tester) async {
      final (auth, api) = freshAuth();
      final repo = _CountingRepository();
      useDesktopSize(tester);
      await tester.pumpWidget(DictaraApp(authController: auth, repository: repo));
      await settle(tester);
      final beforeLogin = repo.listCalls;
      expect(beforeLogin, greaterThan(0)); // the list is built (and so loads) before anyone is signed in

      api.confirm();
      await tester.enterText(find.byKey(const Key('login-username-field')), 'maxim');
      await tester.tap(find.text('Continue with Telegram'));
      await settle(tester);
      await tester.pump(const Duration(milliseconds: 30));
      await settle(tester);

      expect(repo.listCalls, greaterThan(beforeLogin)); // re-fetched under the now-real session, not left stale
    });

    testWidgets('"Continue without an account" signs in immediately, no Telegram step', (tester) async {
      final (auth, api) = freshAuth();
      api.onAnonymous = () => const LoginResult(jwt: 'anon-jwt', displayName: 'Guest');
      await pump(tester, auth);

      await tester.tap(find.byKey(const Key('login-anonymous-link')));
      await settle(tester);

      expect(api.anonymousCalls, 1);
      expect(find.text('Continue with your Telegram account'), findsNothing);
      expect(find.text('Transcriptions'), findsWidgets);
    });

    testWidgets('a refused anonymous login shows the error and stays on the input stage', (tester) async {
      final (auth, api) = freshAuth();
      api.failAnonymousWith = const GatewayException('Could not reach the server');
      await pump(tester, auth);

      await tester.tap(find.byKey(const Key('login-anonymous-link')));
      await settle(tester);

      expect(find.text('Could not reach the server'), findsOneWidget);
      expect(find.text('Continue with your Telegram account'), findsOneWidget);
    });
  });

  group('AuthController', () {
    test('a stored session is applied synchronously, with no request', () {
      final api = FakeAuthApi();
      final store = InMemoryTokenStore(const StoredToken(token: 'saved-jwt', displayName: 'Maxim'));
      String? seenToken;

      final auth = AuthController(api, store, onToken: (t) => seenToken = t);

      expect(auth.isLoggedIn, isTrue);
      expect(auth.displayName, 'Maxim');
      expect(seenToken, 'saved-jwt');
      expect(api.loginCalls, 0);
    });

    test('once confirmed, the session is persisted and onToken fires', () async {
      final api = FakeAuthApi()..confirm(jwt: 'jwt-1', displayName: 'Maxim');
      final store = InMemoryTokenStore();
      final tokens = <String?>[];
      final auth = AuthController(api, store, onToken: tokens.add, pollInterval: const Duration(milliseconds: 5));

      await auth.submitUsername('maxim');
      await Future<void>.delayed(const Duration(milliseconds: 40));

      expect(auth.isLoggedIn, isTrue);
      expect(tokens, ['jwt-1']);
      expect(store.read()?.token, 'jwt-1');
    });

    test('logout clears the store and tells onToken null; a second logout is a no-op', () {
      final store = InMemoryTokenStore(const StoredToken(token: 'jwt', displayName: 'Maxim'));
      final tokens = <String?>[];
      final auth = AuthController(FakeAuthApi(), store, onToken: tokens.add);
      tokens.clear(); // drop the initial restore

      auth.logout();
      auth.logout();

      expect(auth.isLoggedIn, isFalse);
      expect(store.read(), isNull);
      expect(tokens, [null]); // only once
    });

    test('a dropped poll is retried, not fatal', () async {
      var calls = 0;
      final api = FakeAuthApi()
        ..onPoll = (_) {
          calls++;
          if (calls < 3) throw const GatewayException('flaky');
          return const LoginResult(jwt: 'jwt', displayName: 'Maxim');
        };
      final auth = AuthController(api, InMemoryTokenStore(), pollInterval: const Duration(milliseconds: 5));

      await auth.submitUsername('maxim');
      // Until it is done, not for a fixed time: on a busy machine the 5 ms polls come later than that.
      final deadline = DateTime.now().add(const Duration(seconds: 5));
      while (!auth.isLoggedIn && DateTime.now().isBefore(deadline)) {
        await Future<void>.delayed(const Duration(milliseconds: 10));
      }

      expect(auth.isLoggedIn, isTrue);
      expect(calls, greaterThanOrEqualTo(3));
    });

    test('continueAnonymously signs in right away, with no polling', () async {
      final api = FakeAuthApi()..onAnonymous = () => const LoginResult(jwt: 'anon-jwt', displayName: 'Guest');
      final store = InMemoryTokenStore();
      final tokens = <String?>[];
      final auth = AuthController(api, store, onToken: tokens.add);

      await auth.continueAnonymously();

      expect(auth.isLoggedIn, isTrue);
      expect(auth.displayName, 'Guest');
      expect(tokens, ['anon-jwt']);
      expect(store.read()?.token, 'anon-jwt');
      expect(api.loginCalls, 0); // no Telegram request was made
    });

    test('a refused continueAnonymously leaves error set and the session signed out', () async {
      final api = FakeAuthApi()..failAnonymousWith = const GatewayException('Could not reach the server');
      final auth = AuthController(api, InMemoryTokenStore());

      await auth.continueAnonymously();

      expect(auth.isLoggedIn, isFalse);
      expect(auth.error, 'Could not reach the server');
    });

    test('a 429 from the server says to wait, instead of showing the raw reply', () async {
      final api = FakeAuthApi()..failAnonymousWith = const GatewayException('Too Many Requests', statusCode: 429);
      final auth = AuthController(api, InMemoryTokenStore());

      await auth.continueAnonymously();

      expect(auth.isLoggedIn, isFalse);
      expect(auth.error, 'Too many attempts. Please wait a minute and try again.');
    });
  });
}

/// Counts `list()` calls; otherwise the mock's own data.
class _CountingRepository extends FakeTranscriptionRepository {
  int listCalls = 0;

  @override
  Future<TranscriptionPage> list(TranscriptionQuery query, {int page = 0, int size = transcriptionPageSize}) {
    listCalls++;
    return super.list(query, page: page, size: size);
  }
}
