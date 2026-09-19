import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dictara_web/material/main_material.dart';
import 'package:dictara_web/shared/api_client.dart';
import 'package:dictara_web/shared/auth_service.dart';

class _FakeApiClient extends ApiClient {
  @override
  Future<bool> checkHealth() async => true;

  @override
  Future<List<String>> fetchSupportedExtensions() async => ['mp3'];
}

// Uses dart:html (AuthService) — run with: flutter test --platform chrome
void main() {
  testWidgets('App renders smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(
      DictaraApp(authService: AuthService(), api: _FakeApiClient()),
    );
    expect(find.text('Dictara'), findsOneWidget);

    // Dispose the page so its periodic health-check timer doesn't stay pending.
    await tester.pumpWidget(const SizedBox());
  });
}
