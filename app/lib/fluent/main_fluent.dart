import 'package:fluent_ui/fluent_ui.dart';
import 'transcribe_page_fluent.dart';

void main() => runApp(const DictaraFluentApp());

class DictaraFluentApp extends StatelessWidget {
  const DictaraFluentApp({super.key});

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
      home: const TranscribePageFluent(),
    );
  }
}
