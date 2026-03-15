import 'package:flutter/material.dart';
import '../shared/api_client.dart';
import '../shared/auth_service.dart';
import 'transcribe_page.dart';

void main() {
  final authService = AuthService();
  final api = ApiClient();
  authService.addListener(() => api.setToken(authService.token));
  api.setToken(authService.token); // restore persisted token on startup
  runApp(DictaraApp(authService: authService, api: api));
}

class DictaraApp extends StatelessWidget {
  final AuthService authService;
  final ApiClient api;

  const DictaraApp({super.key, required this.authService, required this.api});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Dictara',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorSchemeSeed: const Color(0xFF4A6FA5),
        brightness: Brightness.light,
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorSchemeSeed: const Color(0xFF4A6FA5),
        brightness: Brightness.dark,
        useMaterial3: true,
      ),
      themeMode: ThemeMode.system,
      home: TranscribePage(authService: authService, api: api),
    );
  }
}
