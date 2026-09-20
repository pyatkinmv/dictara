import 'dart:math' show Random;

import 'package:flutter/material.dart';
import 'package:flutter_web_plugins/url_strategy.dart';

import '../app.dart';
import '../data/fake_transcription_repository.dart';

void main() {
  usePathUrlStrategy(); // /tags instead of /#/tags; nginx already falls back to index.html
  runApp(
    DictaraApp(
      // Suggestion actions take up to a second, like a server would, so their spinners are visible.
      repository: FakeTranscriptionRepository(latency: () => Duration(milliseconds: Random().nextInt(1000))),
    ),
  );
}
