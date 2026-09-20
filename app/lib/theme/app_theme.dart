import 'package:flutter/material.dart';

import 'app_palette.dart';

const appFontFamily = 'Inter';

/// Corner radius of cards and panels (`--radius`).
const appRadius = 12.0;

ThemeData buildAppTheme(AppPalette p) {
  final scheme = ColorScheme.fromSeed(
    seedColor: p.accent,
    brightness: Brightness.light,
  ).copyWith(
    primary: p.accent,
    onPrimary: Colors.white,
    surface: p.surface,
    onSurface: p.text,
    outline: p.border,
    error: p.danger,
  );

  final base = ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    fontFamily: appFontFamily,
    scaffoldBackgroundColor: p.background,
    dividerColor: p.border,
    // The design has no ink ripples, only hover/active fills.
    splashFactory: NoSplash.splashFactory,
    highlightColor: Colors.transparent,
  );

  return base.copyWith(
    textTheme: base.textTheme.apply(
      fontFamily: appFontFamily,
      bodyColor: p.text,
      displayColor: p.text,
    ),
    extensions: <ThemeExtension<dynamic>>[p],
  );
}
