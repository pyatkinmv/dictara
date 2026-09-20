import 'package:flutter/material.dart';

/// Design tokens from `designs/1.9/cards-*.html` (the `:root` CSS variables).
///
/// The three 1.9 variants share the same layout and differ only in these
/// colors, so switching the accent is a matter of picking another preset.
@immutable
class AppPalette extends ThemeExtension<AppPalette> {
  const AppPalette({
    required this.accent,
    required this.accentLight,
    required this.accentDark,
    required this.background,
    required this.surface,
    required this.sidebar,
    required this.text,
    required this.text2,
    required this.text3,
    required this.border,
    required this.ok,
    required this.okBg,
    required this.warn,
    required this.warnBg,
    required this.danger,
    required this.dangerBg,
  });

  final Color accent; // --accent
  final Color accentLight; // --al
  final Color accentDark; // --ad
  final Color background; // --bg
  final Color surface; // --surface
  final Color sidebar; // --sb-bg
  final Color text; // --text
  final Color text2; // --text2
  final Color text3; // --text3
  final Color border; // --border
  final Color ok; // --ok
  final Color okBg; // --ok-bg
  final Color warn; // --warn
  final Color warnBg; // --warn-bg
  final Color danger; // #EF4444 (delete, sign out)
  final Color dangerBg; // #FEF2F2

  static const _ok = Color(0xFF16A34A);
  static const _okBg = Color(0xFFDCFCE7);
  static const _warn = Color(0xFFD97706);
  static const _warnBg = Color(0xFFFEF3C7);
  static const _danger = Color(0xFFEF4444);
  static const _dangerBg = Color(0xFFFEF2F2);

  static const teal = AppPalette(
    accent: Color(0xFF0D9488),
    accentLight: Color(0xFFF0FDF9),
    accentDark: Color(0xFF0F766E),
    background: Color(0xFFF2F7F6),
    surface: Color(0xFFFFFFFF),
    sidebar: Color(0xFFE8F4F2),
    text: Color(0xFF0F2922),
    text2: Color(0xFF4B6E6A),
    text3: Color(0xFF8FAEAC),
    border: Color(0xFFD1E7E5),
    ok: _ok,
    okBg: _okBg,
    warn: _warn,
    warnBg: _warnBg,
    danger: _danger,
    dangerBg: _dangerBg,
  );

  static const blue = AppPalette(
    accent: Color(0xFF3B82F6),
    accentLight: Color(0xFFEFF6FF),
    accentDark: Color(0xFF2563EB),
    background: Color(0xFFF0F4F8),
    surface: Color(0xFFFFFFFF),
    sidebar: Color(0xFFEBF2FF),
    text: Color(0xFF1A202C),
    text2: Color(0xFF4A5568),
    text3: Color(0xFFA0AEC0),
    border: Color(0xFFE2E8F0),
    ok: _ok,
    okBg: _okBg,
    warn: _warn,
    warnBg: _warnBg,
    danger: _danger,
    dangerBg: _dangerBg,
  );

  static const violet = AppPalette(
    accent: Color(0xFF7C3AED),
    accentLight: Color(0xFFF5F3FF),
    accentDark: Color(0xFF6D28D9),
    background: Color(0xFFF8F7FF),
    surface: Color(0xFFFFFFFF),
    sidebar: Color(0xFFF4F2FF),
    text: Color(0xFF1A1330),
    text2: Color(0xFF54496A),
    text3: Color(0xFF9D93BE),
    border: Color(0xFFE4DEF8),
    ok: _ok,
    okBg: _okBg,
    warn: _warn,
    warnBg: _warnBg,
    danger: _danger,
    dangerBg: _dangerBg,
  );

  @override
  AppPalette copyWith({
    Color? accent,
    Color? accentLight,
    Color? accentDark,
    Color? background,
    Color? surface,
    Color? sidebar,
    Color? text,
    Color? text2,
    Color? text3,
    Color? border,
    Color? ok,
    Color? okBg,
    Color? warn,
    Color? warnBg,
    Color? danger,
    Color? dangerBg,
  }) {
    return AppPalette(
      accent: accent ?? this.accent,
      accentLight: accentLight ?? this.accentLight,
      accentDark: accentDark ?? this.accentDark,
      background: background ?? this.background,
      surface: surface ?? this.surface,
      sidebar: sidebar ?? this.sidebar,
      text: text ?? this.text,
      text2: text2 ?? this.text2,
      text3: text3 ?? this.text3,
      border: border ?? this.border,
      ok: ok ?? this.ok,
      okBg: okBg ?? this.okBg,
      warn: warn ?? this.warn,
      warnBg: warnBg ?? this.warnBg,
      danger: danger ?? this.danger,
      dangerBg: dangerBg ?? this.dangerBg,
    );
  }

  @override
  AppPalette lerp(ThemeExtension<AppPalette>? other, double t) {
    if (other is! AppPalette) return this;
    return AppPalette(
      accent: Color.lerp(accent, other.accent, t)!,
      accentLight: Color.lerp(accentLight, other.accentLight, t)!,
      accentDark: Color.lerp(accentDark, other.accentDark, t)!,
      background: Color.lerp(background, other.background, t)!,
      surface: Color.lerp(surface, other.surface, t)!,
      sidebar: Color.lerp(sidebar, other.sidebar, t)!,
      text: Color.lerp(text, other.text, t)!,
      text2: Color.lerp(text2, other.text2, t)!,
      text3: Color.lerp(text3, other.text3, t)!,
      border: Color.lerp(border, other.border, t)!,
      ok: Color.lerp(ok, other.ok, t)!,
      okBg: Color.lerp(okBg, other.okBg, t)!,
      warn: Color.lerp(warn, other.warn, t)!,
      warnBg: Color.lerp(warnBg, other.warnBg, t)!,
      danger: Color.lerp(danger, other.danger, t)!,
      dangerBg: Color.lerp(dangerBg, other.dangerBg, t)!,
    );
  }
}

extension AppPaletteContext on BuildContext {
  AppPalette get palette => Theme.of(this).extension<AppPalette>()!;
}
