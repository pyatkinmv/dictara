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
    textTheme: _cssTextTheme(base.textTheme, p),
    extensions: <ThemeExtension<dynamic>>[p],
  );
}

/// Line height of Inter with CSS `line-height: normal`: (ascender 1984 +
/// descender 494) / 2048 units per em.
const _interNormalLineHeight = 1.21;

/// Material 3 gives every text a tall line height (1.43 for body) and extra
/// letter spacing. The design is plain CSS: `line-height: normal` and no
/// tracking. Keep the M3 sizes and weights, but set height and spacing
/// explicitly so text measures like it does in the HTML. (They must be
/// non-null: `MaterialApp` fills every null field from its own text geometry.)
TextTheme _cssTextTheme(TextTheme t, AppPalette p) {
  TextStyle? plain(TextStyle? s) => s == null
      ? null
      : TextStyle(
          fontFamily: appFontFamily,
          fontSize: s.fontSize,
          fontWeight: s.fontWeight,
          color: p.text,
          height: _interNormalLineHeight,
          letterSpacing: 0,
        );

  return TextTheme(
    displayLarge: plain(t.displayLarge),
    displayMedium: plain(t.displayMedium),
    displaySmall: plain(t.displaySmall),
    headlineLarge: plain(t.headlineLarge),
    headlineMedium: plain(t.headlineMedium),
    headlineSmall: plain(t.headlineSmall),
    titleLarge: plain(t.titleLarge),
    titleMedium: plain(t.titleMedium),
    titleSmall: plain(t.titleSmall),
    bodyLarge: plain(t.bodyLarge),
    bodyMedium: plain(t.bodyMedium),
    bodySmall: plain(t.bodySmall),
    labelLarge: plain(t.labelLarge),
    labelMedium: plain(t.labelMedium),
    labelSmall: plain(t.labelSmall),
  );
}
