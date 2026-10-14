import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'package:dictara_web/data/gateway_client.dart';
import 'package:dictara_web/data/transcription_repository.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/widgets/transcription_row.dart';
import 'package:dictara_web/widgets/error_reporter.dart';
import 'package:provider/provider.dart';

import 'support/fake_transcription_repository.dart';
import 'test_utils.dart';

/// A list that fails when told to, otherwise the mock's.
class _FlakyRepository extends FakeTranscriptionRepository {
  bool failLists = false;
  int listCalls = 0;

  @override
  Future<TranscriptionPage> list(TranscriptionQuery query, {int page = 0, int size = transcriptionPageSize}) {
    listCalls++;
    if (failLists) return Future.error(const GatewayException('Could not reach the server'));
    return super.list(query, page: page, size: size);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  group('ErrorReporter', () {
    late GlobalKey<ScaffoldMessengerState> key;
    late DateTime now;
    late ErrorReporter reporter;

    Future<void> pumpHost(WidgetTester tester) async {
      key = GlobalKey<ScaffoldMessengerState>();
      now = DateTime(2025, 7, 1);
      reporter = ErrorReporter(key, now: () => now);
      await tester.pumpWidget(MaterialApp(scaffoldMessengerKey: key, home: const Scaffold(body: SizedBox())));
    }

    testWidgets("shows the gateway's own message", (tester) async {
      await pumpHost(tester);

      reporter.report(const GatewayException('A tag with this name already exists', statusCode: 409));
      await tester.pump();

      expect(find.text('A tag with this name already exists'), findsOneWidget);
      expect(find.byType(SnackBar), findsOneWidget);
    });

    testWidgets('says something plain for an error that is not from the gateway', (tester) async {
      await pumpHost(tester);

      reporter.report(StateError('boom'));
      await tester.pump();

      expect(find.text(ErrorReporter.genericMessage), findsOneWidget);
      expect(find.textContaining('boom'), findsNothing); // the detail is for the console, not for the user
    });

    testWidgets('the same message again right away is shown once, but again after a while', (tester) async {
      await pumpHost(tester);
      const error = GatewayException('Could not reach the server');

      reporter.report(error);
      await tester.pump();
      now = now.add(const Duration(seconds: 2));
      reporter.report(error);
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text('Could not reach the server. Check your connection and try again.'), findsOneWidget);
      expect(find.byType(SnackBar), findsOneWidget);

      await tester.pump(const Duration(seconds: 7)); // the first bar has gone
      now = now.add(ErrorReporter.repeatWindow);
      reporter.report(error);
      await tester.pump();
      expect(find.text('Could not reach the server. Check your connection and try again.'), findsOneWidget);
    });

    testWidgets('trouble with the connection or the server is said plainly, without the technical detail', (tester) async {
      await pumpHost(tester);

      reporter.report(const GatewayException('Could not reach the server: ClientException: Failed to fetch'));
      await tester.pump();
      expect(find.text('Could not reach the server. Check your connection and try again.'), findsOneWidget);

      reporter.report(const GatewayException("The server sent something that is not JSON: Unexpected token '<'", statusCode: 200));
      await tester.pump(const Duration(milliseconds: 600));
      expect(find.text('The server had a problem (HTTP 200). Please try again in a moment.'), findsOneWidget);
      expect(find.textContaining('Unexpected token'), findsNothing);

      reporter.report(const GatewayException('Bad Gateway', statusCode: 502));
      await tester.pump(const Duration(milliseconds: 600));
      expect(find.text('The server had a problem (HTTP 502). Please try again in a moment.'), findsOneWidget);
    });

    testWidgets('a different message replaces the one on screen', (tester) async {
      await pumpHost(tester);

      reporter.report(const GatewayException('First'));
      await tester.pump();
      reporter.report(const GatewayException('Second'));
      await tester.pump(const Duration(milliseconds: 600));

      expect(find.text('Second'), findsOneWidget);
      expect(find.text('First'), findsNothing);
    });

    testWidgets('the app offers the reporter to its screens', (tester) async {
      useDesktopSize(tester);
      await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository()));
      await settle(tester);

      final reporter = Provider.of<ErrorReporter>(tester.element(find.byType(Scaffold).first), listen: false);
      reporter.report(const GatewayException('Speaker not found', statusCode: 404));
      await tester.pump();

      expect(find.text('Speaker not found'), findsOneWidget);
    });
  });

  group('The list refreshing by itself', () {
    testWidgets('a failed refresh is not reported and the next one goes through', (tester) async {
      useDesktopSize(tester);
      final repo = _FlakyRepository();
      await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: repo, pollInterval: const Duration(seconds: 2)));
      await settle(tester);
      // The mock list has a transcription that is still being processed, so the list keeps polling.
      expect(find.byType(TranscriptionRow), findsWidgets);

      repo.failLists = true;
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      final callsWhileDown = repo.listCalls;

      expect(tester.takeException(), isNull);
      expect(find.byType(SnackBar), findsNothing);

      repo.failLists = false;
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();

      expect(repo.listCalls, greaterThan(callsWhileDown)); // it did try again
      expect(find.byType(TranscriptionRow), findsWidgets);
    });
  });
}
