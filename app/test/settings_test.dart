import 'dart:async';

import 'package:dictara_web/app.dart';
import 'package:dictara_web/data/gateway_client.dart';
import 'package:dictara_web/data/mcp_tokens_api.dart';
import 'package:dictara_web/data/profile_api.dart';
import 'package:dictara_web/pages/settings/settings_page.dart';
import 'package:dictara_web/shell/nav_destinations.dart';
import 'package:dictara_web/pages/settings/token_dialogs.dart';
import 'package:dictara_web/state/auth_controller.dart';
import 'package:dictara_web/state/mcp_tokens_controller.dart';
import 'package:dictara_web/state/profile_controller.dart';
import 'package:dictara_web/theme/app_palette.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show LogicalKeyboardKey;
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_mcp_tokens_api.dart';
import 'support/fake_profile_api.dart';
import 'support/fake_transcription_repository.dart';
import 'test_utils.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  late AuthController auth;

  Future<void> open(
    WidgetTester tester, {
    FakeMcpTokensApi? api,
    String location = '/settings',
    Size size = const Size(1280, 800),
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    auth = signedInAuthController(displayName: 'Maxim Piatkin');
    await tester.pumpWidget(
      DictaraApp(
        authController: auth,
        initialLocation: location,
        repository: FakeTranscriptionRepository(),
        mcpTokensApi: api ?? FakeMcpTokensApi(),
        profileController: ProfileController(FakeProfileApi(), auth),
      ),
    );
    await settle(tester);
  }

  final threeStates = [
    fakeToken('OpenCode on laptop', lastUsedAt: DateTime.now()),
    fakeToken('Claude Code, work', status: McpTokenStatus.expiring, access: McpAccess.write),
    fakeToken('Old laptop', status: McpTokenStatus.expired),
  ];

  group('the tabs', () {
    testWidgets('Settings opens MCP access, the first tab, and Profile is the second', (tester) async {
      await open(tester);

      expect(find.text('Settings'), findsWidgets);
      expect(find.text('MCP access'), findsOneWidget);
      expect(find.text('Profile'), findsOneWidget);
      expect(find.text('SERVER ADDRESS'), findsOneWidget); // the content of the first tab
      final order = [for (final s in settingsSections) s.label];
      expect(order, ['MCP access', 'Profile']);
    });

    testWidgets('clicking a tab goes to it, and a section that does not exist falls back to the first', (tester) async {
      await open(tester);

      await tester.tap(find.text('Profile'));
      await settle(tester);
      expect(find.byKey(const Key('profile-name')), findsOneWidget);
      expect(find.text('SERVER ADDRESS'), findsNothing);

      await tester.tap(find.text('MCP access'));
      await settle(tester);
      expect(find.text('SERVER ADDRESS'), findsOneWidget);
    });

    testWidgets('an address of a section that does not exist shows the first one', (tester) async {
      await open(tester, location: '/settings/nonsense');

      expect(find.text('SERVER ADDRESS'), findsOneWidget);
    });

    testWidgets('each tab has its own address', (tester) async {
      await open(tester, location: '/settings/profile');

      expect(find.byKey(const Key('profile-name')), findsOneWidget);
    });
  });

  group('Profile', () {
    testWidgets('shows who you are, the plan and how much of it is used', (tester) async {
      await open(tester, location: '/settings/profile');

      expect(tester.widget<Text>(find.byKey(const Key('profile-name'))).data, 'Maxim Piatkin');
      expect(find.text('MP'), findsWidgets); // the initials, here and in the sidebar
      expect(find.text('Signed in with Telegram'), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(const Key('profile-plan'))).data, 'Free');
      expect(tester.widget<Text>(find.byKey(const Key('profile-usage'))).data, '3 of 10 transcriptions this month');
      expect(find.byType(LinearProgressIndicator), findsOneWidget);
    });

    testWidgets('a plan with no cap says how much is used and draws no bar', (tester) async {
      tester.view.physicalSize = const Size(1280, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      auth = signedInAuthController();
      final profile = ProfileController(
        FakeProfileApi()..onMe = () => const UserProfile(planName: 'Ultra', usage: UsageInfo(used: 42, unit: 'transcriptions')),
        auth,
      );
      await tester.pumpWidget(DictaraApp(authController: auth, initialLocation: '/settings/profile', repository: FakeTranscriptionRepository(), profileController: profile));
      await settle(tester);

      expect(tester.widget<Text>(find.byKey(const Key('profile-usage'))).data, '42 transcriptions this month');
      expect(find.byType(LinearProgressIndicator), findsNothing);
    });

    testWidgets('while the plan is not known yet it says so, and keeps the rest', (tester) async {
      tester.view.physicalSize = const Size(1280, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      auth = signedInAuthController();
      await tester.pumpWidget(DictaraApp(authController: auth, initialLocation: '/settings/profile', repository: FakeTranscriptionRepository()));
      await settle(tester);

      expect(find.text('Loading…'), findsOneWidget); // no profile controller: it never answers
      expect(find.byKey(const Key('profile-name')), findsOneWidget);
      expect(find.byKey(const Key('profile-sign-out')), findsOneWidget);
    });

    testWidgets('Sign out signs out', (tester) async {
      await open(tester, location: '/settings/profile');
      expect(auth.isLoggedIn, isTrue);

      await tester.tap(find.byKey(const Key('profile-sign-out')));
      await settle(tester);

      expect(auth.isLoggedIn, isFalse);
    });
  });

  group('MCP access, the tokens', () {
    testWidgets('with no token it says so and offers to create one', (tester) async {
      await open(tester);

      expect(find.byKey(const Key('no-tokens')), findsOneWidget);
      expect(find.text('No tokens yet'), findsOneWidget);
      expect(find.byKey(const Key('create-token')), findsOneWidget);
      expect(find.byKey(const Key('settings-tab-dot')), findsNothing);
      expect(find.byKey(const Key('expired-banner')), findsNothing);
    });

    testWidgets('shows the server address to copy', (tester) async {
      final copied = captureClipboard(tester);
      await open(tester);

      expect(tester.widget<SelectableText>(find.byKey(const Key('mcp-server-url'))).data, endsWith('/api/mcp'));
      await tester.tap(find.text('Copy'));
      await tester.pump();

      expect(copied, [mcpServerUrl()]);
      expect(find.text('Copied'), findsOneWidget);
      await tester.pump(const Duration(seconds: 2));
      expect(find.text('Copied'), findsNothing);
    });

    testWidgets('a working token says what it is, when it ends and when it was last used', (tester) async {
      await open(tester, api: FakeMcpTokensApi([threeStates[0]]));

      expect(find.text('OpenCode on laptop'), findsOneWidget);
      expect(find.text('read'), findsOneWidget);
      expect(find.textContaining('dtr_Zk3p… · created'), findsOneWidget);
      expect(find.textContaining('expires'), findsWidgets);
      expect(find.textContaining('used today'), findsOneWidget);
      expect(find.text('Revoke'), findsOneWidget);
      expect(find.byKey(const Key('settings-tab-dot')), findsNothing);
      expect(find.byKey(const Key('expired-banner')), findsNothing);
    });

    testWidgets('a token that was never used says so, and one with write access says so', (tester) async {
      await open(tester, api: FakeMcpTokensApi([fakeToken('Fresh', access: McpAccess.write)]));

      expect(find.textContaining('never used'), findsOneWidget);
      expect(find.text('read and write'), findsOneWidget);
    });

    testWidgets('a token that ends soon is marked with the days left, and the tab gets an amber dot', (tester) async {
      await open(tester, api: FakeMcpTokensApi([threeStates[1]]));

      expect(find.text('Expires in 5 days'), findsOneWidget);
      expect(find.text('Revoke'), findsOneWidget);
      final dot = tester.widget<Container>(find.byKey(const Key('settings-tab-dot')));
      expect((dot.decoration! as BoxDecoration).color, AppPalette.teal.warn);
      expect(find.byKey(const Key('expired-banner')), findsNothing); // the card says it; a banner is for what has stopped
    });

    testWidgets('a token that has expired is impossible to miss: a chip, a plain sentence, a banner and a red dot', (tester) async {
      await open(tester, api: FakeMcpTokensApi([threeStates[0], threeStates[2]]));

      expect(find.textContaining('Expired '), findsOneWidget);
      expect(find.text('Stopped working. Create a new token and put it in your agent\'s settings.'), findsOneWidget);
      expect(find.byKey(const Key('expired-banner')), findsOneWidget);
      expect(find.text('1 token has expired. '), findsNothing); // it is one rich text, so look inside it
      expect(find.textContaining('1 token has expired.', findRichText: true), findsOneWidget);
      final dot = tester.widget<Container>(find.byKey(const Key('settings-tab-dot')));
      expect((dot.decoration! as BoxDecoration).color, AppPalette.teal.danger);
      expect(find.text('Create replacement'), findsOneWidget);
      expect(find.text('Delete'), findsOneWidget);
      expect(find.text('Revoke'), findsOneWidget); // the working one still has Revoke
    });

    testWidgets('the banner counts several expired tokens', (tester) async {
      await open(tester, api: FakeMcpTokensApi([fakeToken('One', status: McpTokenStatus.expired), fakeToken('Two', status: McpTokenStatus.expired)]));

      expect(find.textContaining('2 tokens have expired.', findRichText: true), findsOneWidget);
    });

    testWidgets('the red dot is on the tab even when the Profile tab is open', (tester) async {
      await open(tester, api: FakeMcpTokensApi([threeStates[2]]), location: '/settings/profile');

      expect(find.byKey(const Key('settings-tab-dot')), findsOneWidget);
    });

    testWidgets('says how soon an ending token ends: in days, tomorrow, today', (tester) async {
      await open(tester, api: FakeMcpTokensApi([
        fakeToken('Tomorrow', status: McpTokenStatus.expiring, expiresIn: const Duration(days: 1, hours: 2)),
        fakeToken('Today', status: McpTokenStatus.expiring, expiresIn: const Duration(hours: 5)),
        fakeToken('Later', status: McpTokenStatus.expiring, expiresIn: const Duration(days: 13, hours: 1)),
      ]));

      expect(find.text('Expires tomorrow'), findsOneWidget);
      expect(find.text('Expires today'), findsOneWidget);
      expect(find.text('Expires in 13 days'), findsOneWidget);
    });

    testWidgets('says when a token was last used: yesterday, days ago, then the date', (tester) async {
      final now = DateTime.now();
      await open(tester, api: FakeMcpTokensApi([
        fakeToken('A', lastUsedAt: now.subtract(const Duration(days: 1))),
        fakeToken('B', lastUsedAt: now.subtract(const Duration(days: 10))),
        fakeToken('C', lastUsedAt: now.subtract(const Duration(days: 45))),
      ]));

      expect(find.textContaining('used yesterday'), findsOneWidget);
      expect(find.textContaining('used 10 days ago'), findsOneWidget);
      expect(find.textContaining('used 45 days ago'), findsNothing); // the old one is a date
      expect(find.textContaining(RegExp(r'used \d{1,2} [A-Z][a-z]{2}')), findsOneWidget);
    });

    testWidgets('while the list is loading it says so, and does not claim there are no tokens', (tester) async {
      final api = FakeMcpTokensApi()..listGate = Completer<void>();
      await open(tester, api: api);

      expect(find.text('Loading…'), findsOneWidget);
      expect(find.byKey(const Key('no-tokens')), findsNothing);

      api.listGate!.complete();
      await settle(tester);
      expect(find.text('Loading…'), findsNothing);
      expect(find.byKey(const Key('no-tokens')), findsOneWidget);
    });

    testWidgets('the tokens are read again each time Settings opens', (tester) async {
      final api = FakeMcpTokensApi([fakeToken('One')]);
      await open(tester, api: api);
      expect(api.listCalls, 1);

      api.tokens = [...api.tokens, fakeToken('Two')]; // made elsewhere (another device) meanwhile
      await tester.tap(find.text('Tags')); // leave Settings
      await settle(tester);
      await tester.tap(find.text('Settings').last); // and come back
      await settle(tester);

      expect(api.listCalls, 2);
      expect(find.text('Two'), findsOneWidget);
    });

        testWidgets('all three states fit a 375 px screen', (tester) async {
      await open(tester, api: FakeMcpTokensApi(threeStates), size: const Size(375, 800));

      expect(find.text('OpenCode on laptop'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });

  group('MCP access, creating a token', () {
    Future<void> openCreate(WidgetTester tester, FakeMcpTokensApi api) async {
      await open(tester, api: api);
      await tester.tap(find.byKey(const Key('create-token')));
      await settle(tester);
    }

    testWidgets('asks for a name and the access (read only to begin with), and says the token lives 90 days', (tester) async {
      await openCreate(tester, FakeMcpTokensApi());

      expect(find.text('Create token'), findsWidgets);
      expect(find.byKey(const Key('token-name')), findsOneWidget);
      expect(find.byKey(const Key('access-read')), findsOneWidget);
      expect(find.byKey(const Key('access-write')), findsOneWidget);
      expect(find.textContaining('90 days'), findsOneWidget);
      expect(find.textContaining('The agent can search and read'), findsOneWidget);
      expect(find.byType(DropdownButton), findsNothing); // the lifetime is not asked
    });

    testWidgets('write access explains what it adds, and that paid work is never included', (tester) async {
      await openCreate(tester, FakeMcpTokensApi());

      await tester.tap(find.byKey(const Key('access-write')));
      await tester.pump();

      expect(find.textContaining('rename transcripts'), findsOneWidget);
      expect(find.textContaining('never starts paid work'), findsOneWidget);
    });

    testWidgets('without a name nothing is created', (tester) async {
      final api = FakeMcpTokensApi();
      await openCreate(tester, api);

      await tester.tap(find.text('Create token').last);
      await settle(tester);

      expect(api.created, isEmpty);
      expect(find.byKey(const Key('token-name')), findsOneWidget);
    });

    testWidgets('creates the token and shows it once, with how to connect Claude Code and OpenCode', (tester) async {
      final api = FakeMcpTokensApi();
      final copied = captureClipboard(tester);
      await openCreate(tester, api);

      await tester.enterText(find.byKey(const Key('token-name')), '  OpenCode on laptop ');
      await tester.tap(find.byKey(const Key('access-write')));
      await tester.tap(find.text('Create token').last);
      await settle(tester);

      expect(api.created.single.name, 'OpenCode on laptop');
      expect(api.created.single.access, McpAccess.write);
      expect(find.text('Copy your token now'), findsOneWidget);
      final secret = 'dtr_${'S' * 43}';
      expect(tester.widget<SelectableText>(find.byKey(const Key('token-secret'))).data, secret);
      final claude = tester.widget<SelectableText>(find.byKey(const Key('token-claude'))).data!;
      expect(claude, startsWith('claude mcp add --transport http dictara '));
      expect(claude, contains(mcpServerUrl()));
      expect(claude, contains('--header "Authorization: Bearer $secret"'));
      final opencode = tester.widget<SelectableText>(find.byKey(const Key('token-opencode'))).data!;
      expect(opencode, allOf(contains('"type": "remote"'), contains('"url": "${mcpServerUrl()}"'), contains('"oauth": false'), contains('Bearer $secret')));

      await tester.tap(find.text('Copy command'));
      await tester.pump();
      expect(copied.last, claude);
    });

    testWidgets('the token itself can be copied', (tester) async {
      final copied = captureClipboard(tester);
      await openCreate(tester, FakeMcpTokensApi());
      await tester.enterText(find.byKey(const Key('token-name')), 'Mine');
      await tester.tap(find.text('Create token').last);
      await settle(tester);

      await tester.tap(find.text('Copy').last); // the dialog's, the page behind has its own
      await tester.pump();

      expect(copied, ['dtr_${'S' * 43}']);
    });

    testWidgets('the token is shown until the user says they saved it: a click outside does not close it', (tester) async {
      final api = FakeMcpTokensApi();
      await openCreate(tester, api);
      await tester.enterText(find.byKey(const Key('token-name')), 'Mine');
      await tester.tap(find.text('Create token').last);
      await settle(tester);

      await tester.tapAt(const Offset(20, 20)); // the dimmed backdrop
      await settle(tester);
      expect(find.text('Copy your token now'), findsOneWidget);

      await tester.sendKeyEvent(LogicalKeyboardKey.escape);
      await settle(tester);
      expect(find.text('Copy your token now'), findsOneWidget);

      await tester.tap(find.text('I have saved it'));
      await settle(tester);
      expect(find.text('Copy your token now'), findsNothing);
      expect(find.text('Mine'), findsOneWidget); // now in the list
      expect(find.byKey(const Key('no-tokens')), findsNothing);
    });

    testWidgets('a refusal (too many tokens) is shown in the dialog, which stays', (tester) async {
      final api = FakeMcpTokensApi()..failCreateWith = const GatewayException('You already have 10 working tokens; revoke one first');
      await openCreate(tester, api);

      await tester.enterText(find.byKey(const Key('token-name')), 'One too many');
      await tester.tap(find.text('Create token').last);
      await settle(tester);

      expect(find.byKey(const Key('token-error')), findsOneWidget);
      expect(find.textContaining('revoke one first'), findsOneWidget);
      expect(find.byKey(const Key('token-name')), findsOneWidget);
      expect(find.text('Copy your token now'), findsNothing);
    });

    testWidgets('Cancel closes it and creates nothing', (tester) async {
      final api = FakeMcpTokensApi();
      await openCreate(tester, api);

      await tester.tap(find.text('Cancel'));
      await settle(tester);

      expect(find.byKey(const Key('token-name')), findsNothing);
      expect(api.created, isEmpty);
    });

    testWidgets('"Create replacement" opens the form with the name and access of the ended token', (tester) async {
      final api = FakeMcpTokensApi([fakeToken('Old laptop', status: McpTokenStatus.expired, access: McpAccess.write)]);
      await open(tester, api: api);

      await tester.tap(find.text('Create replacement'));
      await settle(tester);

      expect(tester.widget<TextField>(find.byKey(const Key('token-name'))).controller!.text, 'Old laptop');
      await tester.tap(find.text('Create token').last);
      await settle(tester);
      expect(api.created.single.access, McpAccess.write);
    });
  });

  group('MCP access, revoking a token', () {
    testWidgets('asks first, and revoking removes the token at once', (tester) async {
      final api = FakeMcpTokensApi([fakeToken('Doomed')]);
      await open(tester, api: api);

      await tester.tap(find.text('Revoke'));
      await settle(tester);
      expect(find.textContaining('It stops working immediately'), findsOneWidget);

      await tester.tap(find.text('Revoke').last);
      await settle(tester);

      expect(api.revoked, ['doomed']);
      expect(find.text('Doomed'), findsNothing);
      expect(find.byKey(const Key('no-tokens')), findsOneWidget);
    });

    testWidgets('Cancel keeps the token', (tester) async {
      final api = FakeMcpTokensApi([fakeToken('Keep me')]);
      await open(tester, api: api);

      await tester.tap(find.text('Revoke'));
      await settle(tester);
      await tester.tap(find.text('Cancel'));
      await settle(tester);

      expect(api.revoked, isEmpty);
      expect(find.text('Keep me'), findsOneWidget);
    });

    testWidgets('an ended token is deleted at once, with no question, and the red dot and the banner go', (tester) async {
      final api = FakeMcpTokensApi([threeStates[2]]);
      await open(tester, api: api);
      expect(find.byKey(const Key('settings-tab-dot')), findsOneWidget);

      await tester.tap(find.text('Delete'));
      await settle(tester);

      expect(api.revoked, ['old-laptop']);
      expect(find.byKey(const Key('settings-tab-dot')), findsNothing);
      expect(find.byKey(const Key('expired-banner')), findsNothing);
    });
  });

  group('the tokens controller and the API', () {
    test('a failed read leaves the list as it was, and signing out clears it', () async {
      final api = FakeMcpTokensApi([fakeToken('Mine')]);
      final authController = signedInAuthController();
      final controller = McpTokensController(api, authController);

      await controller.load();
      expect(controller.tokens.map((t) => t.name), ['Mine']);
      expect(controller.loaded, isTrue);

      api.failListWith = const GatewayException('down');
      await controller.load();
      expect(controller.tokens.map((t) => t.name), ['Mine']);

      authController.logout();
      expect(controller.tokens, isEmpty);
      expect(controller.loaded, isFalse);
      controller.dispose();
    });

    test('a failed revoke is thrown for the app to report, and the list stays as it was', () async {
      final api = FakeMcpTokensApi([fakeToken('Mine')])..failRevokeWith = const GatewayException('Token not found', statusCode: 404);
      final controller = McpTokensController(api, signedInAuthController());
      await controller.load();

      await expectLater(controller.revoke('mine'), throwsA(isA<GatewayException>()));

      expect(controller.tokens.map((t) => t.name), ['Mine']);
      controller.dispose();
    });

    test('a refused creation is thrown for the dialog to show, and nothing is added', () async {
      final api = FakeMcpTokensApi()..failCreateWith = const GatewayException('A token needs a name', statusCode: 400);
      final controller = McpTokensController(api, signedInAuthController());
      await controller.load();

      await expectLater(controller.create(name: '', access: McpAccess.read), throwsA(isA<GatewayException>()));

      expect(controller.tokens, isEmpty);
      controller.dispose();
    });

    test('every address under /settings belongs to the Settings item of the sidebar', () {
      expect(destinationFor('/settings')?.label, 'Settings');
      expect(destinationFor('/settings/profile')?.label, 'Settings');
      expect(destinationFor('/settings/mcp-access')?.label, 'Settings');
    });

    test('what needs attention is an ended token or one that ends soon', () async {
      final controller = McpTokensController(
        FakeMcpTokensApi([fakeToken('A'), fakeToken('B', status: McpTokenStatus.expiring), fakeToken('C', status: McpTokenStatus.expired)]),
        signedInAuthController(),
      );
      await controller.load();

      expect(controller.expiredCount, 1);
      expect(controller.expiringCount, 1);
      expect(controller.needsAttention, isTrue);
      controller.dispose();
    });
  });
}
