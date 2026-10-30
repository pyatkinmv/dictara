import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'package:dictara_web/data/gateway_client.dart';
import 'package:dictara_web/state/transcription_list_controller.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'support/fake_transcription_repository.dart';
import 'test_utils.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  late FakeTranscriptionRepository repo;

  Future<void> open(WidgetTester tester, {Size? size}) async {
    if (size == null) {
      useDesktopSize(tester);
    } else {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
    }
    repo = FakeTranscriptionRepository();
    await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: repo, initialLocation: '/transcriptions/0'));
    await settle(tester);
  }

  String location(WidgetTester tester) =>
      GoRouter.of(tester.element(find.byType(Scaffold).first)).state.uri.path;

  group('Deleting a transcription', () {
    testWidgets('the header has a quiet trash button next to .txt and Copy, and it deletes nothing by itself', (tester) async {
      await open(tester);

      expect(find.byKey(const Key('delete-transcription')), findsOneWidget);
      expect(find.text('Delete'), findsNothing); // an icon, not a labelled button
      await tester.tap(find.byKey(const Key('delete-transcription')));
      await settle(tester);

      expect(find.byKey(const Key('delete-transcription-dialog')), findsOneWidget);
      expect(repo.deleteCalls, isEmpty);
    });

    testWidgets('the window names the transcription and says what goes, what stays and that it cannot be undone', (tester) async {
      await open(tester);

      await tester.tap(find.byKey(const Key('delete-transcription')));
      await settle(tester);

      expect(find.text('Delete "Quarterly Review Meeting"?'), findsOneWidget);
      expect(find.textContaining('permanently removes the transcript, the summary and the audio file'), findsOneWidget);
      expect(find.textContaining('tags and speakers stay in the library'), findsOneWidget);
      expect(find.text("This can't be undone."), findsOneWidget);
      expect(find.textContaining('backup'), findsNothing); // nothing about backup copies
      expect(find.byType(Checkbox), findsNothing); // and no tick to confirm
      expect(find.text('Delete forever'), findsOneWidget);
    });

    testWidgets('cancelling keeps the transcription and the page', (tester) async {
      await open(tester);

      await tester.tap(find.byKey(const Key('delete-transcription')));
      await settle(tester);
      await tester.tap(find.text('Cancel'));
      await settle(tester);

      expect(repo.deleteCalls, isEmpty);
      expect(find.byKey(const Key('delete-transcription-dialog')), findsNothing);
      expect(location(tester), '/transcriptions/0');
    });

    testWidgets('confirming deletes it, goes back to the list, and the list no longer has it', (tester) async {
      await open(tester);

      await tester.tap(find.byKey(const Key('delete-transcription')));
      await settle(tester);
      await tester.tap(find.text('Delete forever'));
      await settle(tester);

      expect(repo.deleteCalls, ['0']);
      expect(location(tester), '/transcriptions');
      expect(find.text('Quarterly Review Meeting'), findsNothing);
      final list = tester.element(find.byType(Scaffold).first).read<TranscriptionListController>();
      expect(list.items.any((i) => i.id == '0'), isFalse);
    });

    testWidgets("a refusal (it is still being processed) is shown with the gateway's words, and the page stays", (tester) async {
      await open(tester);
      repo.deleteError = const GatewayException('This transcription is still being processed. Try again when it is done.', statusCode: 409);

      await tester.tap(find.byKey(const Key('delete-transcription')));
      await settle(tester);
      await tester.tap(find.text('Delete forever'));
      await settle(tester);

      expect(find.textContaining('still being processed'), findsOneWidget);
      expect(location(tester), '/transcriptions/0');
      expect(find.text('Quarterly Review Meeting'), findsWidgets);
    });

    testWidgets('on a phone the button and the window fit', (tester) async {
      await open(tester, size: const Size(375, 812));

      await tester.tap(find.byKey(const Key('delete-transcription')));
      await settle(tester);

      expect(find.byKey(const Key('delete-transcription-dialog')), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  });
}
