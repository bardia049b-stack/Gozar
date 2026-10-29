import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gozar/core.dart';
import 'package:gozar/main.dart';

class FakeCore extends Core {
  FakeCore({this.signedIn = false, this.language = ''});

  bool signedIn;
  String language;
  bool isRunning = false;
  String? selectedHost;
  final requests = <String>[];

  static const places = [
    Place(
      code: 'REC',
      country: 'Recommended',
      city: '',
      host: 'recommended.example',
      recommended: true,
    ),
    Place(
      code: 'NL',
      country: 'Netherlands',
      city: 'Amsterdam',
      host: 'amsterdam.example',
      recommended: false,
    ),
  ];

  CoreState get current => CoreState(
        signedIn: signedIn,
        email: signedIn ? 'example@example.com' : '',
        language: language,
        mode: 'tun',
        version: '1.9.0',
        servers: signedIn ? places : const [],
        server: places.firstWhere(
          (place) => place.host == selectedHost,
          orElse: () => places.first,
        ),
      );

  @override
  bool get running => isRunning;

  @override
  Future<void> start() async => isRunning = true;

  @override
  Future<CoreReply> call(String method, [Map<String, dynamic>? params]) async {
    requests.add(method);
    if (method == 'language') {
      final choice = params!['language'] as String;
      language = choice == 'system' ? '' : choice;
    }
    if (method == 'server') selectedHost = params!['host'] as String;
    if (method == 'login') {
      if ((params!['code'] as String).isEmpty) {
        return CoreReply(state: current, error: 'code needed', needsCode: true);
      }
      signedIn = true;
    }
    if (method == 'logout') signedIn = false;
    return CoreReply(state: current);
  }

  @override
  void stop() => isRunning = false;
}

void main() {
  testWidgets('language can be changed from the native login screen',
      (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.localeTestValue = const Locale('en');
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearLocaleTestValue);
    final core = FakeCore();
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    expect(find.text('Sign in'), findsWidgets);
    expect(find.text('Language'), findsOneWidget);
    expect(find.text('Google'), findsNothing);
    expect(find.text('Apple'), findsNothing);
    await tester.tap(find.text('Language'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('پارسی'));
    await tester.pumpAndSettle();
    expect(find.text('ورود'), findsWidgets);
    expect(core.language, 'fa');
    expect(
        Localizations.localeOf(tester.element(find.text('ورود').first))
            .languageCode,
        'fa');
    expect(Directionality.of(tester.element(find.text('ورود').first)),
        TextDirection.rtl);
    expect(core.requests, isNot(contains('connect')));
  });

  testWidgets('settings changes language and sign out returns to login',
      (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final core = FakeCore(signedIn: true, language: 'fa');
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    expect(find.text('Firefox VPN'), findsOneWidget);
    await tester.tap(find.byTooltip('تنظیمات'));
    await tester.pumpAndSettle();
    expect(find.text('حساب Firefox'), findsOneWidget);
    await tester.tap(find.text('زبان'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('English'));
    await tester.pumpAndSettle();
    expect(find.text('Disconnected'), findsOneWidget);
    expect(Directionality.of(tester.element(find.text('Disconnected'))),
        TextDirection.ltr);
    await tester.tap(find.byTooltip('Settings'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sign out'));
    await tester.pumpAndSettle();
    expect(find.text('Sign in'), findsWidgets);
    expect(core.requests, isNot(contains('connect')));
  });

  testWidgets('server selection uses the chosen host', (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final core = FakeCore(signedIn: true, language: 'en');
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Server'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Netherlands · Amsterdam'));
    await tester.pumpAndSettle();
    expect(core.selectedHost, 'amsterdam.example');
    expect(core.requests, isNot(contains('connect')));
  });
  testWidgets('verification code flow stays inside the native interface',
      (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final core = FakeCore(language: 'en');
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(0), 'person@example.com');
    await tester.enterText(find.byType(TextField).at(1), 'fake-password');
    await tester.tap(find.widgetWithText(FilledButton, 'Sign in'));
    await tester.pumpAndSettle();
    expect(find.text('Verification code'), findsOneWidget);
    await tester.enterText(find.byType(TextField).at(2), '123456');
    await tester.tap(find.widgetWithText(FilledButton, 'Verify and sign in'));
    await tester.pumpAndSettle();
    expect(find.text('Firefox VPN'), findsOneWidget);
    expect(core.requests, isNot(contains('connect')));
  });
}
