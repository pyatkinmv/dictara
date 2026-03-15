import 'package:fluent_ui/fluent_ui.dart';
import '../shared/api_client.dart';
import '../shared/auth_service.dart';
import 'transcribe_page_fluent.dart';

void main() {
  final authService = AuthService();
  final api = ApiClient();
  authService.addListener(() => api.setToken(authService.token));
  api.setToken(authService.token); // restore persisted token on startup
  runApp(DictaraFluentApp(authService: authService, api: api));
}

class DictaraFluentApp extends StatelessWidget {
  final AuthService authService;
  final ApiClient api;

  const DictaraFluentApp({super.key, required this.authService, required this.api});

  @override
  Widget build(BuildContext context) {
    return FluentApp(
      title: 'Dictara',
      debugShowCheckedModeBanner: false,
      theme: FluentThemeData(
        accentColor: const Color(0xFF4A6FA5).toAccentColor(),
        brightness: Brightness.light,
      ),
      darkTheme: FluentThemeData(
        accentColor: const Color(0xFF4A6FA5).toAccentColor(),
        brightness: Brightness.dark,
      ),
      themeMode: ThemeMode.system,
      home: TranscribePageFluent(authService: authService, api: api),
    );
  }
}
