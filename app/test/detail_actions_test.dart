import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/app.dart';
import 'support/fake_transcription_repository.dart';
import 'package:dictara_web/models/transcription_detail.dart';
import 'package:dictara_web/util/format.dart';

import 'test_utils.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  test('the file name is the title, without what file systems refuse', () {
    expect(transcriptFileName('Quarterly Review Meeting'), 'Quarterly Review Meeting.txt');
    expect(transcriptFileName('Q2: notes/draft?'), 'Q2 notes draft.txt');
    expect(transcriptFileName('  a   b  '), 'a b.txt');
    expect(transcriptFileName('***'), 'transcript.txt');
    expect(transcriptFileName(''), 'transcript.txt');
  });

  plainTranscriptTests();

  group('the .txt and Copy buttons of the detail page', () {
    late FakeTextSaver saver;

    Future<void> open(WidgetTester tester) async {
      useDesktopSize(tester);
      saver = FakeTextSaver();
      await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: FakeTranscriptionRepository(), initialLocation: '/transcriptions/0', textSaver: saver));
      await settle(tester);
    }

    testWidgets('.txt saves the transcript under the title', (tester) async {
      await open(tester);

      await tester.tap(find.text('.txt'));
      await settle(tester);

      expect(saver.saved, hasLength(1));
      expect(saver.saved.single.fileName, 'Quarterly Review Meeting.txt');
      expect(saver.saved.single.text, contains('Good morning everyone.'));
      expect(find.text('Saved'), findsOneWidget);

      await tester.pump(const Duration(seconds: 2));
      expect(find.text('.txt'), findsOneWidget); // the label comes back
    });

    testWidgets('Copy puts the same text on the clipboard', (tester) async {
      final copied = captureClipboard(tester);
      await open(tester);

      await tester.tap(find.text('Copy'));
      await settle(tester);

      expect(copied, hasLength(1));
      expect(copied.single, contains('Good morning everyone.'));
      expect(find.text('Copied'), findsOneWidget);

      await tester.pump(const Duration(seconds: 2));
      expect(find.text('Copy'), findsOneWidget);
    });

    testWidgets('a download that fails says so', (tester) async {
      await open(tester);
      saver.error = StateError('blocked');

      await tester.tap(find.text('.txt'));
      await settle(tester);

      expect(find.text('Failed'), findsOneWidget);
      await tester.pump(const Duration(seconds: 2));
    });

    testWidgets('the text follows a rename', (tester) async {
      await open(tester);
      await tester.tap(find.byTooltip('Rename'));
      await tester.pump();
      await tester.enterText(find.byType(EditableText), 'Renamed: talk');
      await tester.tap(find.byTooltip('Save'));
      await settle(tester);

      await tester.tap(find.text('.txt'));
      await settle(tester);

      expect(saver.saved.single.fileName, 'Renamed talk.txt');
      await tester.pump(const Duration(seconds: 2));
    });
  });
}

/// A transcript as the gateway sends it when the audio was not diarized: no speaker on any segment.
class _PlainTranscriptRepository extends FakeTranscriptionRepository {
  @override
  Future<TranscriptionDetail?> get(String id) async {
    final detail = await super.get(id);
    if (detail == null) return null;
    return TranscriptionDetail(
      item: detail.item,
      segments: [
        const Segment(start: Duration.zero, text: 'First thought about the roadmap.'),
        const Segment(start: Duration(seconds: 20), text: 'Second thought about hiring.'),
      ],
      originalFilename: detail.originalFilename,
      fileSizeBytes: detail.fileSizeBytes,
    );
  }
}

void plainTranscriptTests() {
  group('a transcript without diarization', () {
    testWidgets('shows the text in one block with no speaker header', (tester) async {
      useDesktopSize(tester);
      await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), initialLocation: '/transcriptions/4', repository: _PlainTranscriptRepository()));
      await settle(tester);

      expect(find.text('First thought about the roadmap.'), findsOneWidget);
      expect(find.text('Second thought about hiring.'), findsOneWidget);
      expect(find.text('0:00'), findsOneWidget);
      expect(find.text('0:20'), findsOneWidget);
      expect(find.byTooltip('Change speaker'), findsNothing);
      expect(find.textContaining('Speaker '), findsNothing);
    });
  });
}
