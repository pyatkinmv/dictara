import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'package:dictara_web/data/gateway_client.dart';
import 'package:dictara_web/state/transcription_detail_controller.dart';
import 'package:provider/provider.dart';

import 'support/fake_transcription_repository.dart';
import 'test_utils.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  late FakeTranscriptionRepository repo;

  Future<void> openOverview(WidgetTester tester) async {
    repo = FakeTranscriptionRepository();
    await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: repo, initialLocation: '/transcriptions/0'));
    await settle(tester);
    await tester.tap(find.text('Overview'));
    await settle(tester);
  }

  Future<void> confirm(WidgetTester tester) async {
    await tester.tap(find.byKey(const Key('regenerate-summary')));
    await settle(tester);
    await tester.tap(find.text('Regenerate').last);
    await settle(tester);
  }

  group('Regenerate summary', () {
    testWidgets('the button is in the summary card, and asking first says what will happen', (tester) async {
      useDesktopSize(tester);
      await openOverview(tester);

      expect(find.byKey(const Key('regenerate-summary')), findsOneWidget);

      await tester.tap(find.byKey(const Key('regenerate-summary')));
      await settle(tester);

      expect(find.text('Regenerate the summary?'), findsOneWidget);
      expect(find.textContaining('a new title, tags and speakers are suggested'), findsOneWidget);
      expect(find.textContaining('once for this recording'), findsOneWidget);
    });

    testWidgets('cancelling changes nothing', (tester) async {
      useDesktopSize(tester);
      await openOverview(tester);

      await tester.tap(find.byKey(const Key('regenerate-summary')));
      await settle(tester);
      await tester.tap(find.text('Cancel'));
      await settle(tester);

      expect(repo.regenerateCalls, isEmpty);
      expect(find.byKey(const Key('regenerate-summary')), findsOneWidget);
      expect(find.text('Regenerate the summary?'), findsNothing);
    });

    testWidgets('confirming starts it: the old summary stays and the card says it is running', (tester) async {
      useDesktopSize(tester);
      await openOverview(tester);

      await confirm(tester);

      expect(repo.regenerateCalls, ['0']);
      expect(find.byKey(const Key('regenerating')), findsOneWidget);
      expect(find.byKey(const Key('regenerate-summary')), findsNothing);
      expect(find.textContaining('Q2 closed strong'), findsOneWidget);
    });

    testWidgets('when it is done the new summary is shown, with the day, and there is no button any more', (tester) async {
      useDesktopSize(tester);
      await openOverview(tester);
      await confirm(tester);

      repo.finishRegeneration('0', 'A fresh summary written in the second pass.');
      await tester.pump(const Duration(seconds: 4));
      await tester.pump(const Duration(milliseconds: 50));

      expect(find.text('A fresh summary written in the second pass.'), findsOneWidget);
      expect(find.textContaining('Q2 closed strong'), findsNothing);
      expect(find.byKey(const Key('regenerating')), findsNothing);
      expect(find.byKey(const Key('regenerate-summary')), findsNothing);
      expect(find.text('Regenerated 24 Jun'), findsOneWidget);
    });

    testWidgets('a refusal from the gateway is passed on and the button stays as the gateway says', (tester) async {
      useDesktopSize(tester);
      await openOverview(tester);
      repo.regenerateError = const GatewayException('The summary service is not available', statusCode: 409);

      final controller = tester.element(find.byType(Scaffold).first).read<TranscriptionDetailController>();
      await expectLater(controller.regenerateSummary(), throwsA(isA<GatewayException>()));
      await settle(tester);

      expect(find.byKey(const Key('regenerating')), findsNothing);
      expect(find.byKey(const Key('regenerate-summary')), findsOneWidget);
    });

    testWidgets('on a phone the card keeps its button next to the label', (tester) async {
      tester.view.physicalSize = const Size(375, 812);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await openOverview(tester);

      expect(find.byKey(const Key('regenerate-summary')), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });
}
