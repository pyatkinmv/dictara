import 'dart:math' show Random;

import 'package:flutter/material.dart';
import 'package:flutter_web_plugins/url_strategy.dart';

import '../app.dart';
import '../data/fake_transcription_repository.dart';
import '../data/gateway_client.dart';
import '../data/http_transcription_repository.dart';
import '../data/transcription_repository.dart';

/// Where the data comes from: `--dart-define=BACKEND=mock` (default, the in-memory mock) or
/// `BACKEND=local` (the gateway on localhost through the dev server's `/api` proxy, see
/// `web_dev_config.yaml` and `docs/local-backend-integration.md`). A migration aid: it goes away once every
/// screen is connected.
const _backend = String.fromEnvironment('BACKEND', defaultValue: 'mock');

void main() {
  usePathUrlStrategy(); // /tags instead of /#/tags; nginx already falls back to index.html

  final TranscriptionRepository repository;
  final Duration pollInterval;
  switch (_backend) {
    case 'mock':
      repository = FakeTranscriptionRepository(
        // Actions take up to a second, like a server would, so their spinners are visible.
        latency: () => Duration(milliseconds: Random().nextInt(1000)),
        // An uploaded file waits 4 s, is transcribed for 20 s and is then done.
        jobTiming: const JobTiming(queue: Duration(seconds: 4), processing: Duration(seconds: 20)),
      );
      pollInterval = const Duration(seconds: 2);
    case 'local':
      repository = HttpTranscriptionRepository(GatewayClient());
      pollInterval = const Duration(seconds: 3);
    default:
      throw StateError("Unknown BACKEND '$_backend': use 'mock' or 'local'.");
  }

  // The list follows the progress of files that are still being transcribed.
  runApp(DictaraApp(repository: repository, pollInterval: pollInterval));
}
