import 'package:flutter/material.dart';
import 'transcribe_page.dart';

void main() {
  runApp(const DictaraApp());
}

class DictaraApp extends StatelessWidget {
  const DictaraApp({super.key});

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
      home: const TranscribePage(),
    );
  }
}
