import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/theme/app_palette.dart';
import 'package:dictara_web/theme/app_theme.dart';
import 'package:dictara_web/widgets/status_badge.dart';
import 'package:dictara_web/widgets/transcription_row.dart';

import 'test_utils.dart';

Widget _row(Transcription item) => MaterialApp(
  theme: buildAppTheme(AppPalette.teal),
  home: Scaffold(
    body: Center(
      child: SizedBox(width: 800, child: TranscriptionRow(item: item, onTap: () {})),
    ),
  ),
);

Transcription _item({
  TranscriptionStatus status = TranscriptionStatus.done,
  Duration? duration,
  int? pct,
}) => Transcription(
  id: 'abc',
  title: 'Weekly call',
  duration: duration,
  uploadedAt: DateTime(2026, 9, 20, 12),
  status: status,
  processingPct: pct,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);

  testWidgets('a failed transcription says so, in the danger colors', (tester) async {
    await tester.pumpWidget(_row(_item(status: TranscriptionStatus.failed)));

    expect(find.text('Failed'), findsOneWidget);
    expect(find.byIcon(Icons.error_rounded), findsWidgets);
    final p = AppPalette.teal;
    expect(TranscriptionStatus.failed.foreground(p), p.danger);
    expect(TranscriptionStatus.failed.background(p), p.dangerBg);
  });

  testWidgets('an unknown duration shows no duration at all', (tester) async {
    await tester.pumpWidget(_row(_item()));

    expect(find.text('Weekly call'), findsOneWidget);
    expect(find.textContaining('min'), findsNothing);
    expect(find.byIcon(Icons.schedule_rounded), findsNothing);
    expect(find.text('20 Sep'), findsOneWidget);
  });

  testWidgets('a known duration is shown', (tester) async {
    await tester.pumpWidget(_row(_item(duration: const Duration(minutes: 14, seconds: 22))));
    expect(find.text('14 min'), findsOneWidget);
  });

  testWidgets('processing without a percentage does not claim 0%', (tester) async {
    await tester.pumpWidget(_row(_item(status: TranscriptionStatus.processing)));
    expect(find.text('Processing…'), findsOneWidget);
    expect(find.textContaining('%'), findsNothing);
  });

  testWidgets('processing with a percentage shows it', (tester) async {
    await tester.pumpWidget(_row(_item(status: TranscriptionStatus.processing, pct: 41)));
    expect(find.text('41% — transcribing…'), findsOneWidget);
  });
}
