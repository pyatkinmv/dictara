import 'dart:math' show Random;

import 'package:flutter/material.dart';
import 'package:flutter_web_plugins/url_strategy.dart';

import '../app.dart';
import '../data/fake_transcription_repository.dart';

void main() {
  usePathUrlStrategy(); // /tags instead of /#/tags; nginx already falls back to index.html
  runApp(
    DictaraApp(
      repository: FakeTranscriptionRepository(
        // Actions take up to a second, like a server would, so their spinners are visible.
        latency: () => Duration(milliseconds: Random().nextInt(1000)),
        // An uploaded file waits 4 s, is transcribed for 20 s and is then done.
        jobTiming: const JobTiming(queue: Duration(seconds: 4), processing: Duration(seconds: 20)),
      ),
      // Lets the list follow that progress.
      pollInterval: const Duration(seconds: 2),
    ),
  );
}
