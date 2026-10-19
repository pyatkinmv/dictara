import 'dart:async';

import 'package:dictara_web/models/speaker.dart';
import 'package:dictara_web/models/tag.dart';
import 'package:dictara_web/models/transcription_detail.dart';
import 'package:dictara_web/state/transcription_detail_controller.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_transcription_repository.dart';

/// A repository whose three reads of an open page wait until the test lets them go, and which says which were asked for.
class _Gated extends FakeTranscriptionRepository {
  final asked = <String>[];
  var gates = <String, Completer<void>>{};

  void arm() {
    asked.clear();
    gates = {for (final name in ['get', 'tags', 'speakers']) name: Completer<void>()};
  }

  void release(String name) => gates[name]!.complete();

  @override
  Future<TranscriptionDetail?> get(String id) {
    asked.add('get');
    final gate = gates['get'];
    return gate == null ? super.get(id) : gate.future.then((_) => super.get(id));
  }

  @override
  Future<List<Tag>> tags() {
    asked.add('tags');
    final gate = gates['tags'];
    return gate == null ? super.tags() : gate.future.then((_) => super.tags());
  }

  @override
  Future<List<Speaker>> speakers() {
    asked.add('speakers');
    final gate = gates['speakers'];
    return gate == null ? super.speakers() : gate.future.then((_) => super.speakers());
  }
}

void main() {
  late _Gated repo;
  late TranscriptionDetailController controller;

  setUp(() {
    repo = _Gated()..arm();
    controller = TranscriptionDetailController(repo);
  });

  tearDown(() => controller.dispose());

  Future<void> idle() => Future<void>.delayed(Duration.zero);

  group('opening a transcription', () {
    test('asks for the transcription, the tags and the speakers together, not one after another', () async {
      final opening = controller.open('0');
      await idle();

      expect(repo.asked, unorderedEquals(['get', 'tags', 'speakers'])); // all three out before any is answered
      expect(controller.current, isNull);

      for (final name in ['get', 'tags', 'speakers']) {
        repo.release(name);
      }
      await opening;
    });

    test('the page is drawn only when the last of the three is in', () async {
      final opening = controller.open('0');
      await idle();

      repo.release('get');
      repo.release('tags');
      await idle();
      expect(controller.current, isNull, reason: 'the speakers are not in yet');
      expect(controller.speakers, isEmpty);

      repo.release('speakers');
      await opening;

      expect(controller.current?.item.id, '0');
      expect(controller.library, isNotEmpty);
      expect(controller.speakers, isNotEmpty);
    });

    test('a read that fails fails the opening, as it did one after another', () async {
      final failing = _FailingSpeakers();
      final c = TranscriptionDetailController(failing);
      addTearDown(c.dispose);

      await expectLater(c.open('0'), throwsA(isA<StateError>()));
      expect(c.current, isNull);
    });
  });

  test('settling a speaker reads the transcription and the speakers together', () async {
    repo.gates = {}; // let the opening through
    await controller.open('0');
    repo.arm();

    final assigning = controller.assignSpeaker({0}, 'Maria Chen', false);
    await idle();
    await idle();

    expect(repo.asked, unorderedEquals(['get', 'speakers']));

    repo.release('get');
    repo.release('speakers');
    await assigning;
  });
}

class _FailingSpeakers extends FakeTranscriptionRepository {
  @override
  Future<List<Speaker>> speakers() => Future.error(StateError('no speakers'));
}
