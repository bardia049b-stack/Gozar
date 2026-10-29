import 'package:flutter/material.dart';

const dark = Color(0xFF78001A);
const mid = Color(0xFFE4002B);
const soft = Color(0xFFFF5165);
const light = Color(0xFFFFDADF);
const ink = Color(0xFF0A0B0E);
const card = Color(0xFF1D1819);
const text = Color(0xFFF4F5F9);
const dim = Color(0xFF9EA1AF);

ThemeData buildTheme() {
  final base = ThemeData.dark(useMaterial3: true);
  return base.copyWith(
    scaffoldBackgroundColor: ink,
    colorScheme: base.colorScheme.copyWith(
      primary: soft,
      onPrimary: const Color(0xFF4A0010),
      secondary: const Color(0xFFFFB2B8),
      surface: card,
      onSurface: text,
    ),
    textTheme: base.textTheme.apply(
      bodyColor: text,
      displayColor: text,
      fontFamily: 'Vazirmatn',
    ),
    cardTheme: CardTheme(
      color: card,
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: mid,
        foregroundColor: Colors.white,
        minimumSize: const Size.fromHeight(52),
        shape: const StadiumBorder(),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(foregroundColor: soft),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: const Color(0xFF22252D),
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 16),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: Color(0xFF4B4B51)),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: Color(0xFF4B4B51)),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: const BorderSide(color: soft, width: 1.5),
      ),
    ),
  );
}
