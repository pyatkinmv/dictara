import 'dart:io';

import 'package:dictara_web/app_version.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('the version shown in the app is the one in pubspec.yaml', () {
    final line = File('pubspec.yaml').readAsLinesSync().firstWhere((l) => l.startsWith('version:'));
    final inPubspec = line.substring('version:'.length).trim().split('+').first;

    expect(appVersion, inPubspec, reason: 'bump lib/app_version.dart and pubspec.yaml together');
  });

  test('it is a semantic version with an optional pre-release label', () {
    expect(appVersion, matches(RegExp(r'^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$')));
  });
}
