import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'core.dart';
import 'strings.dart';
import 'theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const GozarApp());
}

class GozarApp extends StatefulWidget {
  const GozarApp({super.key, this.core});

  final Core? core;

  @override
  State<GozarApp> createState() => _GozarAppState();
}

class _GozarAppState extends State<GozarApp> with WidgetsBindingObserver {
  String _language = '';
  String _theme = 'system';

  bool get _persian => _language.isEmpty
      ? WidgetsBinding.instance.platformDispatcher.locale.languageCode == 'fa'
      : _language == 'fa';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeLocales(List<Locale>? locales) {
    if (_language.isEmpty && mounted) setState(() {});
  }

  void _changeLanguage(String value) {
    final normalized = value == 'system' ? '' : value;
    if (_language != normalized) setState(() => _language = normalized);
  }

  void _changeTheme(String value) {
    if (_theme != value) setState(() => _theme = value);
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
        debugShowCheckedModeBanner: false,
        title: 'Gozar',
        theme: buildTheme(lightMode: true),
        darkTheme: buildTheme(),
        themeMode: _theme == 'light'
            ? ThemeMode.light
            : _theme == 'dark'
                ? ThemeMode.dark
                : ThemeMode.system,
        locale: Locale(_persian ? 'fa' : 'en'),
        supportedLocales: const [Locale('fa'), Locale('en')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: GozarScreen(
          core: widget.core,
          persian: _persian,
          language: _language,
          onLanguageChanged: _changeLanguage,
          onThemeChanged: _changeTheme,
        ),
      );
}

class GozarScreen extends StatefulWidget {
  const GozarScreen({
    super.key,
    this.core,
    required this.persian,
    required this.language,
    required this.onLanguageChanged,
    required this.onThemeChanged,
  });

  final Core? core;
  final bool persian;
  final String language;
  final ValueChanged<String> onLanguageChanged;
  final ValueChanged<String> onThemeChanged;

  @override
  State<GozarScreen> createState() => _GozarScreenState();
}

class _GozarScreenState extends State<GozarScreen> {
  late final Core _core;
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _confirm = TextEditingController();
  final _code = TextEditingController();
  final _captcha = TextEditingController();
  CoreState _state = const CoreState();
  Timer? _timer;
  bool _starting = true;
  bool _busy = false;
  bool _connectionBusy = false;
  bool _refreshing = false;
  bool _loadingServers = false;
  bool _needsCode = false;
  bool _creating = false;
  bool _adding = false;
  bool _showPassword = false;
  String? _captchaImage;
  String? _error;
  String? _fatal;

  Words get words => Words(widget.persian);

  @override
  void initState() {
    super.initState();
    _core = widget.core ?? Core();
    _core.onExit = () {
      if (mounted) setState(() => _fatal = 'core disconnected');
    };
    unawaited(_start());
  }

  @override
  void dispose() {
    _timer?.cancel();
    _core.stop();
    _email.dispose();
    _password.dispose();
    _confirm.dispose();
    _code.dispose();
    _captcha.dispose();
    super.dispose();
  }

  Future<void> _start() async {
    try {
      await _core.start();
      var reply = await _core.call('state');
      if (reply.state?.mode != 'tun' && reply.state?.live == false) {
        reply = await _core.call('mode', {'tunnel': true});
      }
      if (!mounted) return;
      setState(() {
        _state = reply.state ?? const CoreState();
        _email.text = _state.email;
        _starting = false;
      });
      widget.onLanguageChanged(_state.language);
      widget.onThemeChanged(_state.theme);
      _timer = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
      if (_state.signedIn) {
        unawaited(_loadServers());
        unawaited(_usage());
      }
    } on Object catch (error) {
      if (mounted) {
        setState(() {
          _fatal = error.toString();
          _starting = false;
        });
      }
    }
  }

  void _apply(CoreReply reply) {
    if (!mounted || reply.state == null) return;
    final state = reply.state!;
    setState(() => _state = state);
    if (state.language != widget.language) {
      widget.onLanguageChanged(state.language);
    }
    widget.onThemeChanged(state.theme);
  }

  Future<void> _refresh() async {
    if (_busy || _refreshing || !_core.running) return;
    _refreshing = true;
    try {
      _apply(await _core.call('state'));
    } on Object {
      return;
    } finally {
      _refreshing = false;
    }
  }

  Future<void> _loadServers() async {
    if (_loadingServers || !_core.running) return;
    if (mounted) setState(() => _loadingServers = true);
    try {
      final reply = await _core.call('servers');
      _apply(reply);
      if (reply.error != null && mounted) setState(() => _error = '@servers');
    } on Object {
      if (mounted) setState(() => _error = '@servers');
    } finally {
      if (mounted) setState(() => _loadingServers = false);
    }
  }

  Future<void> _usage() async {
    try {
      _apply(await _core.call('usage'));
    } on Object {
      return;
    }
  }

  Future<void> _login() async {
    if (_busy) return;
    if (_email.text.trim().isEmpty || _password.text.isEmpty) {
      setState(() => _error = '@credentials');
      return;
    }
    if (_creating && _password.text != _confirm.text) {
      setState(() => _error = '@passwordMismatch');
      return;
    }
    if (_needsCode && _code.text.trim().isEmpty) {
      setState(() => _error = '@code');
      return;
    }
    if (_captchaImage != null && _captcha.text.trim().isEmpty) {
      setState(() => _error = '@captcha');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final reply = await _core.call(_creating ? 'register' : 'login', {
        'email': _email.text.trim(),
        'password': _password.text,
        'code': _code.text.trim(),
        'captcha': _captcha.text.trim(),
      });
      _apply(reply);
      if (!mounted) return;
      if (reply.captchaImage != null) {
        _captcha.clear();
        setState(() {
          _captchaImage = reply.captchaImage;
          _error = null;
        });
      } else {
        _captcha.clear();
        _captchaImage = null;
        if (reply.needsCode) {
          _code.clear();
          setState(() {
            _needsCode = true;
            _error = null;
          });
        } else if (reply.error != null) {
          setState(() => _error = reply.error);
        } else if (_state.signedIn) {
          _password.clear();
          _confirm.clear();
          _code.clear();
          _needsCode = false;
          _creating = false;
          _adding = false;
          unawaited(_loadServers());
          unawaited(_usage());
        }
      }
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _switchAuthentication() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await _core.call('cancel_auth');
    } on Object {
      return;
    } finally {
      if (mounted) {
        _code.clear();
        _confirm.clear();
        _captcha.clear();
        setState(() {
          _creating = !_creating;
          _needsCode = false;
          _captchaImage = null;
          _error = null;
          _busy = false;
        });
      }
    }
  }

  Future<void> _toggleConnection() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _connectionBusy = true;
      _error = null;
    });
    try {
      final reply = await _core.call(_state.live ? 'disconnect' : 'connect');
      _apply(reply);
      if (mounted && reply.error != null) setState(() => _error = reply.error);
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _connectionBusy = false;
        });
      }
    }
  }

  Future<void> _selectServer(Place place) async {
    if (_busy || _state.live) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final reply = await _core.call('server', {'host': place.host});
      _apply(reply);
      if (reply.error != null && mounted) setState(() => _error = reply.error);
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _signOut() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final reply = await _core.call('logout');
      _apply(reply);
      if (mounted && reply.error != null) {
        setState(() => _error = reply.error);
      } else {
        _password.clear();
        _confirm.clear();
        _code.clear();
        _captcha.clear();
        _captchaImage = null;
        _needsCode = false;
        _creating = false;
        _adding = false;
        _email.clear();
      }
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String _message(String error) {
    final lower = error.toLowerCase();
    if (error == '@credentials') return words.credentialsRequired;
    if (error == '@code') return words.codeRequired;
    if (error == '@passwordMismatch') return words.passwordMismatch;
    if (error == '@captcha') return words.securityRequired;
    if (lower.contains('security check') || lower.contains('security-check')) {
      return words.securityFailed;
    }
    if (lower.contains('verification link required')) return words.verifyLink;
    if (error == '@servers') return words.serverError;
    if (error == '@browser') return words.browserError;
    if (lower.contains('core not found')) return words.coreMissing;
    if (lower.contains('core disconnected') ||
        lower.contains('core not running')) {
      return words.coreStopped;
    }
    if (lower.contains('administrator') || lower.contains('needs root')) {
      return Platform.isWindows
          ? words.windowsAdminRequired
          : words.adminRequired;
    }
    if (lower.contains('timed out') || lower.contains('timeout')) {
      return words.timeout;
    }
    if (lower.contains('not signed in')) return words.signInRequired;
    if (lower.contains('socketexception') ||
        lower.contains('connection refused')) {
      return words.connectionError;
    }
    return error.replaceFirst('Bad state: ', '');
  }

  Future<void> _openPage(String address) async {
    try {
      if (Platform.isWindows) {
        await Process.start(
            'rundll32', ['url.dll,FileProtocolHandler', address]);
      } else if (Platform.isMacOS) {
        await Process.start('open', [address]);
      } else {
        await Process.start('xdg-open', [address]);
      }
    } on Object {
      if (mounted) setState(() => _error = '@browser');
    }
  }

  Future<void> _chooseLanguage() async {
    if (_busy) return;
    final choice = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(words.language),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final entry in [
              ('system', words.system),
              ('fa', 'پارسی'),
              ('en', 'English'),
            ])
              RadioListTile<String>(
                title: Text(entry.$2),
                value: entry.$1,
                groupValue:
                    widget.language.isEmpty ? 'system' : widget.language,
                onChanged: (value) => Navigator.pop(dialogContext, value),
              ),
          ],
        ),
      ),
    );
    if (choice == null || !mounted) return;
    try {
      final reply = await _core.call('language', {'language': choice});
      _apply(reply);
      if (reply.error != null && mounted) setState(() => _error = reply.error);
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    }
  }

  void _showServers() {
    if (_busy || _state.live) return;
    if (_state.servers.isEmpty) {
      unawaited(_loadServers());
      return;
    }
    showDialog<void>(
      context: context,
      builder: (dialogContext) => ServerDialog(
        places: _state.servers,
        selected: _state.server?.host,
        words: words,
        onSelect: (place) {
          Navigator.pop(dialogContext);
          unawaited(_selectServer(place));
        },
      ),
    );
  }

  Future<bool> _requestSetting(
      String method, Map<String, dynamic> params) async {
    try {
      final reply = await _core.call(method, params);
      _apply(reply);
      if (reply.error != null) {
        if (mounted) setState(() => _error = reply.error);
        return false;
      }
      return true;
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
      return false;
    }
  }

  Future<void> _chooseTheme() async {
    final choice = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(words.appearance),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final option in [
              ('system', words.system),
              ('light', words.lightTheme),
              ('dark', words.darkTheme),
            ])
              RadioListTile<String>(
                title: Text(option.$2),
                value: option.$1,
                groupValue: _state.theme,
                onChanged: (value) => Navigator.pop(dialogContext, value),
              ),
          ],
        ),
      ),
    );
    if (choice != null) await _requestSetting('theme', {'theme': choice});
  }

  Future<void> _editChain() async {
    if (_state.live || _state.connecting) {
      await _info(words.proxyChain, words.switchDisconnect);
      return;
    }
    final value = await showDialog<String>(
      context: context,
      builder: (context) =>
          ProxyChainDialog(words: words, initial: _state.chain.join('\n')),
    );
    if (value != null) {
      final hops = value
          .split(RegExp(r'[\n,]'))
          .map((item) => item.trim())
          .where((item) => item.isNotEmpty)
          .toList();
      await _requestSetting('chain', {'hops': hops});
    }
  }

  Future<void> _info(String title, String message) async {
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: Text(words.close))
        ],
      ),
    );
  }

  Future<bool> _confirmDisconnect() async {
    if (!_state.live && !_state.connecting) return true;
    return await showDialog<bool>(
          context: context,
          builder: (dialogContext) => AlertDialog(
            title: Text(words.manageAccounts),
            content: Text(words.switchDisconnect),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(dialogContext, false),
                  child: Text(words.cancel)),
              TextButton(
                  onPressed: () => Navigator.pop(dialogContext, true),
                  child: Text(words.disconnect)),
            ],
          ),
        ) ??
        false;
  }

  Future<void> _showAccounts({bool fromLogin = false}) async {
    if (_busy) return;
    final choice = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(words.savedAccounts),
        content: SizedBox(
          width: 380,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final account in _state.accounts)
                  ListTile(
                    leading:
                        const Icon(Icons.account_circle_outlined, color: soft),
                    title:
                        Text(account.email, textDirection: TextDirection.ltr),
                    subtitle: account.active ? Text(words.activeAccount) : null,
                    trailing: account.active
                        ? const Icon(Icons.check, color: soft)
                        : null,
                    onTap: account.active
                        ? null
                        : () => Navigator.pop(dialogContext, account.id),
                  ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: Text(words.close)),
          if (!fromLogin)
            TextButton.icon(
              onPressed: () => Navigator.pop(dialogContext, '__add__'),
              icon: const Icon(Icons.person_add_alt_1, color: soft),
              label: Text(words.addAccount),
            ),
        ],
      ),
    );
    if (choice == null || !mounted) return;
    if (choice == '__add__') {
      await _beginAddAccount();
    } else {
      await _switchAccount(choice);
    }
  }

  Future<void> _beginAddAccount() async {
    if (_busy || !await _confirmDisconnect() || !mounted) return;
    if (_state.live || _state.connecting) {
      setState(() => _busy = true);
      try {
        final reply = await _core.call('disconnect');
        _apply(reply);
        if (reply.error != null ||
            reply.state?.live == true ||
            reply.state?.connecting == true) {
          if (mounted) {
            setState(() => _error = reply.error ?? words.connectionError);
          }
          return;
        }
      } on Object catch (error) {
        if (mounted) setState(() => _error = error.toString());
        return;
      } finally {
        if (mounted) setState(() => _busy = false);
      }
    }
    if (!mounted) return;
    _email.clear();
    _password.clear();
    _confirm.clear();
    _code.clear();
    _captcha.clear();
    setState(() {
      _adding = true;
      _creating = false;
      _needsCode = false;
      _captchaImage = null;
      _error = null;
    });
  }

  Future<void> _cancelAdd() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await _core.call('cancel_auth');
    } on Object {
      return;
    } finally {
      _password.clear();
      _confirm.clear();
      _code.clear();
      _captcha.clear();
      if (mounted) {
        setState(() {
          _adding = false;
          _creating = false;
          _needsCode = false;
          _captchaImage = null;
          _error = null;
          _busy = false;
          _email.text = _state.email;
        });
      }
    }
  }

  Future<void> _switchAccount(String id) async {
    if (_busy || !await _confirmDisconnect() || !mounted) return;
    setState(() => _busy = true);
    try {
      final reply = await _core.call('switch_account', {'id': id});
      _apply(reply);
      if (reply.error != null) {
        if (mounted) setState(() => _error = reply.error);
        return;
      }
      _email.text = _state.email;
      _password.clear();
      _confirm.clear();
      _code.clear();
      if (mounted) setState(() => _adding = false);
      unawaited(_usage());
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _chooseSystemEmail() async {
    if (_busy) return;
    setState(() => _busy = true);
    CoreReply? reply;
    try {
      reply = await _core.call('system_accounts');
      _apply(reply);
    } on Object {
      reply = null;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    if (!mounted) return;
    if (reply == null || reply.error != null) {
      await _info(words.systemAccounts, words.systemPickerUnavailable);
      return;
    }
    if (reply.systemEmails.isEmpty) {
      await _info(words.systemAccounts, words.noSystemAccounts);
      return;
    }
    final selected = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(words.systemAccounts),
        content: SizedBox(
          width: 380,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final email in reply!.systemEmails)
                  ListTile(
                    leading:
                        const Icon(Icons.account_circle_outlined, color: soft),
                    title: Text(email, textDirection: TextDirection.ltr),
                    onTap: () => Navigator.pop(dialogContext, email),
                  ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: Text(words.cancel))
        ],
      ),
    );
    if (selected != null && mounted) _email.text = selected;
  }

  Future<void> _showDonation() async {
    const address = 'UQDxiU-jPFK_TgB0wOzZndhf3IKJLTLm8Vg_Uaif7_C2eCL3';
    final copy = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(words.donate),
        content: SizedBox(
          width: 380,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(words.donateMessage),
              const SizedBox(height: 16),
              const SelectableText(address, textDirection: TextDirection.ltr),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: Text(words.close)),
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: Text(words.copyAddress)),
        ],
      ),
    );
    if (copy == true) {
      await Clipboard.setData(const ClipboardData(text: address));
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(words.addressCopied)));
      }
    }
  }

  Future<void> _checkUpdates() async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 10);
    try {
      final request = await client.getUrl(Uri.parse(
          'https://api.github.com/repos/bardia049b-stack/Gozar1/releases/latest'));
      request.headers.set(HttpHeaders.userAgentHeader, 'Gozar desktop');
      final response =
          await request.close().timeout(const Duration(seconds: 15));
      if (response.statusCode != 200) throw const FormatException();
      final data = jsonDecode(await utf8.decoder.bind(response).join())
          as Map<String, dynamic>;
      final tag = data['tag_name'] as String? ?? '';
      final url = data['html_url'] as String? ?? '';
      const current = '1.9.0';
      List<int> version(String value) => RegExp(r'\d+')
          .allMatches(value)
          .take(3)
          .map((match) => int.parse(match.group(0)!))
          .toList();
      final available = version(tag);
      final installed = version(current);
      bool newer = false;
      if (available.length == 3 && installed.length == 3) {
        for (var i = 0; i < 3; i++) {
          if (available[i] != installed[i]) {
            newer = available[i] > installed[i];
            break;
          }
        }
      }
      if (!mounted) return;
      final open = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(words.updates),
          content: Text(
              newer ? '${words.updateAvailable} $tag' : words.updateCurrent),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(dialogContext, false),
                child: Text(words.close)),
            if (newer &&
                url.startsWith(
                    'https://github.com/bardia049b-stack/Gozar1/releases/'))
              TextButton(
                  onPressed: () => Navigator.pop(dialogContext, true),
                  child: Text(words.openRelease)),
          ],
        ),
      );
      if (open == true) await _openPage(url);
    } on Object {
      if (mounted) await _info(words.updates, words.updateFailed);
    } finally {
      client.close(force: true);
    }
  }

  void _showSettings() {
    showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, updateDialog) => AlertDialog(
          title: Text(words.settings),
          content: SizedBox(
            width: 400,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading:
                        const Icon(Icons.account_circle_outlined, color: soft),
                    title: Text(words.account),
                    subtitle:
                        Text(_state.email, textDirection: TextDirection.ltr),
                    onTap: () {
                      Navigator.pop(dialogContext);
                      unawaited(_showAccounts());
                    },
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.language, color: soft),
                    title: Text(words.language),
                    subtitle: Text(widget.language == 'fa'
                        ? 'پارسی'
                        : widget.language == 'en'
                            ? 'English'
                            : words.system),
                    onTap: () {
                      Navigator.pop(dialogContext);
                      unawaited(_chooseLanguage());
                    },
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.palette_outlined, color: soft),
                    title: Text(words.appearance),
                    subtitle: Text(_state.theme == 'light'
                        ? words.lightTheme
                        : _state.theme == 'dark'
                            ? words.darkTheme
                            : words.system),
                    onTap: () {
                      Navigator.pop(dialogContext);
                      unawaited(_chooseTheme());
                    },
                  ),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    secondary: const Icon(Icons.history, color: soft),
                    title: Text(words.rememberServer),
                    value: _state.recents,
                    onChanged: _busy
                        ? null
                        : (value) async {
                            await _requestSetting(
                                'recents', {'enabled': value});
                            if (context.mounted) updateDialog(() {});
                          },
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.alt_route, color: soft),
                    title: Text(words.proxyChain),
                    subtitle: Text(
                        _state.chain.isEmpty
                            ? words.noProxyChain
                            : _state.chain.join(' → '),
                        textDirection:
                            _state.chain.isEmpty ? null : TextDirection.ltr),
                    onTap: () {
                      Navigator.pop(dialogContext);
                      unawaited(_editChain());
                    },
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.route_outlined, color: soft),
                    title: Text(words.routing),
                    subtitle: Text(words.desktopRoutingLimits),
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.system_update_alt, color: soft),
                    title: Text(words.updates),
                    onTap: () {
                      Navigator.pop(dialogContext);
                      unawaited(_checkUpdates());
                    },
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.info_outline, color: soft),
                    title: Text(words.about),
                    subtitle: Text('Gozar ${_state.version} · AGPL-3.0'),
                    onTap: () {
                      Navigator.pop(dialogContext);
                      unawaited(_openPage(
                          'https://github.com/bardia049b-stack/Gozar1'));
                    },
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.favorite_outline, color: soft),
                    title: Text(words.donate),
                    subtitle: const Text('TON'),
                    onTap: () {
                      Navigator.pop(dialogContext);
                      unawaited(_showDonation());
                    },
                  ),
                  const SizedBox(height: 8),
                  OutlinedButton.icon(
                    onPressed: _busy
                        ? null
                        : () {
                            Navigator.pop(dialogContext);
                            unawaited(_signOut());
                          },
                    icon: const Icon(Icons.logout, color: soft),
                    label: Text(words.signOut),
                  ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(dialogContext),
                child: Text(words.close))
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        body: SafeArea(
          child: _starting
              ? const Center(child: CircularProgressIndicator())
              : _fatal != null
                  ? Center(
                      child: Padding(
                        padding: const EdgeInsets.all(32),
                        child: Text(_message(_fatal!),
                            textAlign: TextAlign.center),
                      ),
                    )
                  : _state.signedIn && !_adding
                      ? _home()
                      : _loginScreen(),
        ),
      );

  Widget _loginScreen() => Stack(
        children: [
          Positioned(
            left: -55,
            right: -55,
            top: -100,
            child: IgnorePointer(
              child: Opacity(
                opacity: 0.32,
                child: Image.asset('assets/firefox.png', height: 420),
              ),
            ),
          ),
          Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(24, 100, 24, 24),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 440),
                child: Container(
                  padding: const EdgeInsets.all(24),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.surface,
                    borderRadius: BorderRadius.circular(28),
                    border: Border.all(
                        color: Theme.of(context).colorScheme.outlineVariant),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        _creating ? words.createTitle : words.signIn,
                        style: Theme.of(context)
                            .textTheme
                            .headlineMedium
                            ?.copyWith(
                              fontWeight: FontWeight.w700,
                            ),
                      ),
                      const SizedBox(height: 12),
                      Text(
                        _creating
                            ? words.createDescription
                            : words.signInDescription,
                        style: TextStyle(color: muted(context)),
                      ),
                      const SizedBox(height: 28),
                      TextField(
                        controller: _email,
                        enabled: !_busy,
                        textDirection: TextDirection.ltr,
                        keyboardType: TextInputType.emailAddress,
                        autofillHints: const [AutofillHints.email],
                        decoration: InputDecoration(
                          labelText: words.email,
                          prefixIcon:
                              Icon(Icons.email_outlined, color: muted(context)),
                          suffixIcon: IconButton(
                            tooltip: words.systemAccounts,
                            onPressed: _busy ? null : _chooseSystemEmail,
                            icon: const Icon(Icons.account_circle_outlined,
                                color: soft),
                          ),
                        ),
                      ),
                      const SizedBox(height: 14),
                      TextField(
                        controller: _password,
                        enabled: !_busy,
                        textDirection: TextDirection.ltr,
                        obscureText: !_showPassword,
                        autofillHints: const [AutofillHints.password],
                        onSubmitted: (_) => _login(),
                        decoration: InputDecoration(
                          labelText: words.password,
                          prefixIcon:
                              Icon(Icons.lock_outline, color: muted(context)),
                          suffixIcon: IconButton(
                            onPressed: () =>
                                setState(() => _showPassword = !_showPassword),
                            icon: Icon(
                              _showPassword
                                  ? Icons.visibility_off_outlined
                                  : Icons.visibility_outlined,
                              color: muted(context),
                            ),
                          ),
                        ),
                      ),
                      if (_creating) ...[
                        const SizedBox(height: 14),
                        TextField(
                          controller: _confirm,
                          enabled: !_busy,
                          textDirection: TextDirection.ltr,
                          obscureText: !_showPassword,
                          autofillHints: const [AutofillHints.newPassword],
                          onSubmitted: (_) => _login(),
                          decoration: InputDecoration(
                            labelText: words.repeatPassword,
                            prefixIcon:
                                Icon(Icons.lock_outline, color: muted(context)),
                          ),
                        ),
                      ],
                      if (_needsCode) ...[
                        const SizedBox(height: 14),
                        Text(words.checkEmail,
                            style: TextStyle(color: muted(context))),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _code,
                          enabled: !_busy,
                          textDirection: TextDirection.ltr,
                          keyboardType: TextInputType.text,
                          autocorrect: false,
                          onSubmitted: (_) => _login(),
                          decoration: InputDecoration(
                            labelText: words.verificationCode,
                            prefixIcon:
                                Icon(Icons.pin_outlined, color: muted(context)),
                          ),
                        ),
                      ],
                      if (_captchaImage != null) ...[
                        const SizedBox(height: 14),
                        Text(words.securityCheck,
                            style: Theme.of(context).textTheme.titleSmall),
                        const SizedBox(height: 6),
                        Text(words.securityInstruction,
                            style: TextStyle(color: muted(context))),
                        const SizedBox(height: 8),
                        Semantics(
                          label: words.securityCheck,
                          child: Image.memory(
                            base64Decode(_captchaImage!.split(',').last),
                            height: 88,
                            fit: BoxFit.contain,
                          ),
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _captcha,
                          enabled: !_busy,
                          textDirection: TextDirection.ltr,
                          autocorrect: false,
                          onSubmitted: (_) => _login(),
                          decoration: InputDecoration(
                            labelText: words.securityAnswer,
                            prefixIcon: Icon(Icons.shield_outlined,
                                color: muted(context)),
                          ),
                        ),
                      ],
                      if (!_creating)
                        Align(
                          alignment: AlignmentDirectional.centerEnd,
                          child: TextButton(
                            onPressed: _busy
                                ? null
                                : () => _openPage(
                                    'https://accounts.firefox.com/reset_password'),
                            child: Text(words.forgotPassword),
                          ),
                        ),
                      if (_error != null) ...[
                        ErrorText(_message(_error!)),
                        const SizedBox(height: 12),
                      ],
                      const SizedBox(height: 8),
                      FilledButton(
                        onPressed: _busy ? null : _login,
                        child: _busy
                            ? const SmallProgress()
                            : Text(_needsCode
                                ? (_creating
                                    ? words.verifyAndCreate
                                    : words.verifyAndSignIn)
                                : (_creating
                                    ? words.createAction
                                    : words.signIn)),
                      ),
                      const SizedBox(height: 12),
                      TextButton(
                        onPressed: _busy ? null : _switchAuthentication,
                        child: FittedBox(
                          fit: BoxFit.scaleDown,
                          child: Text(
                            _creating ? words.haveAccount : words.createAccount,
                            maxLines: 1,
                          ),
                        ),
                      ),
                      if (!_adding && _state.accounts.isNotEmpty)
                        TextButton.icon(
                          onPressed: _busy
                              ? null
                              : () => _showAccounts(fromLogin: true),
                          icon: const Icon(Icons.switch_account_outlined,
                              color: soft),
                          label: Text(words.savedAccounts),
                        ),
                      if (_adding)
                        TextButton(
                          onPressed: _busy ? null : _cancelAdd,
                          child: Text(words.cancel),
                        ),
                      const SizedBox(height: 12),
                      Text(
                        words.passwordNote,
                        textAlign: TextAlign.center,
                        style: Theme.of(context)
                            .textTheme
                            .bodySmall
                            ?.copyWith(color: muted(context)),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          PositionedDirectional(
            top: 8,
            end: 12,
            child: TextButton.icon(
              onPressed: _busy ? null : _chooseLanguage,
              icon: const Icon(Icons.language, size: 20),
              label: Text(words.language),
            ),
          ),
        ],
      );

  Widget _home() => Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 18),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 440),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Image.asset('assets/firefox.png', width: 42, height: 42),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text('Firefox VPN',
                              style: TextStyle(
                                  fontSize: 20, fontWeight: FontWeight.w700)),
                          Text(
                            _state.email,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            textDirection: TextDirection.ltr,
                            style: TextStyle(color: muted(context)),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      tooltip: words.settings,
                      onPressed: _showSettings,
                      icon: const Icon(Icons.settings_outlined, color: soft),
                    ),
                  ],
                ),
                const SizedBox(height: 22),
                Container(
                  padding: const EdgeInsets.fromLTRB(22, 24, 22, 22),
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [Color(0xFF8D001F), Color(0xFFD7002A)],
                    ),
                    borderRadius: BorderRadius.circular(28),
                  ),
                  child: Column(
                    children: [
                      Semantics(
                        button: true,
                        label: _state.live ? words.disconnect : words.connect,
                        child: Material(
                          color: const Color(0xFFFFF0E6),
                          shape: const CircleBorder(),
                          child: InkWell(
                            customBorder: const CircleBorder(),
                            onTap: _busy ? null : _toggleConnection,
                            child: SizedBox(
                              width: 106,
                              height: 106,
                              child: Center(
                                child: _connectionBusy
                                    ? const SizedBox(
                                        width: 30,
                                        height: 30,
                                        child: CircularProgressIndicator(
                                            color: dark, strokeWidth: 3),
                                      )
                                    : const Icon(
                                        Icons.power_settings_new_rounded,
                                        size: 51,
                                        color: dark),
                              ),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 18),
                      Text(
                        _connectionBusy
                            ? (_state.live
                                ? words.disconnecting
                                : words.connecting)
                            : _state.live
                                ? words.connected
                                : words.disconnected,
                        textAlign: TextAlign.center,
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                              color: Colors.white,
                              fontWeight: FontWeight.w700,
                            ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        _state.live ? words.tapDisconnect : words.tapConnect,
                        textAlign: TextAlign.center,
                        style: const TextStyle(color: Color(0xFFFFDADF)),
                      ),
                      if (_state.limit > 0) ...[
                        const SizedBox(height: 20),
                        Row(
                          children: [
                            Expanded(
                                child: Text(words.remaining,
                                    style:
                                        const TextStyle(color: Colors.white))),
                            Text(
                              '${_size(_state.remaining)} / ${_size(_state.limit)}',
                              textDirection: TextDirection.ltr,
                              style: const TextStyle(color: Colors.white),
                            ),
                          ],
                        ),
                        const SizedBox(height: 8),
                        LinearProgressIndicator(
                          value:
                              (_state.remaining / _state.limit).clamp(0.0, 1.0),
                          minHeight: 5,
                          color: const Color(0xFFFFDADF),
                          backgroundColor: const Color(0x66FFFFFF),
                          borderRadius: BorderRadius.circular(8),
                        ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(height: 14),
                Card(
                  margin: EdgeInsets.zero,
                  child: ListTile(
                    enabled: !_busy && !_state.live,
                    leading: const Icon(Icons.public, color: soft),
                    title: Text(words.server),
                    subtitle: Text(
                      _loadingServers
                          ? words.loadingServers
                          : _state.server?.host.isNotEmpty == true
                              ? _state.server!.label(widget.persian)
                              : words.chooseServer,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(color: muted(context)),
                    ),
                    trailing: Icon(
                      widget.persian ? Icons.chevron_left : Icons.chevron_right,
                      color: soft,
                    ),
                    onTap: _showServers,
                  ),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                        child: RateCard(
                            icon: Icons.south_rounded,
                            label: words.download,
                            value: _speed(_state.rateDown))),
                    const SizedBox(width: 12),
                    Expanded(
                        child: RateCard(
                            icon: Icons.north_rounded,
                            label: words.upload,
                            value: _speed(_state.rateUp))),
                  ],
                ),
                const SizedBox(height: 16),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(Icons.info_outline, color: soft, size: 18),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        words.tunnelNotice,
                        style: Theme.of(context)
                            .textTheme
                            .bodySmall
                            ?.copyWith(color: muted(context)),
                      ),
                    ),
                  ],
                ),
                if (_error != null) ...[
                  const SizedBox(height: 16),
                  ErrorText(_message(_error!)),
                ],
              ],
            ),
          ),
        ),
      );
}

class SmallProgress extends StatelessWidget {
  const SmallProgress({super.key});

  @override
  Widget build(BuildContext context) => const SizedBox(
        width: 22,
        height: 22,
        child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
      );
}

class ErrorText extends StatelessWidget {
  const ErrorText(this.message, {super.key});

  final String message;

  @override
  Widget build(BuildContext context) => Text(
        message,
        textAlign: TextAlign.center,
        style: const TextStyle(color: soft),
      );
}

class RateCard extends StatelessWidget {
  const RateCard(
      {super.key,
      required this.icon,
      required this.label,
      required this.value});

  final IconData icon;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(icon, color: soft, size: 19),
                  const SizedBox(width: 5),
                  Text(label, style: TextStyle(color: muted(context))),
                ],
              ),
              const SizedBox(height: 5),
              Text(value, textDirection: TextDirection.ltr),
            ],
          ),
        ),
      );
}

class ProxyChainDialog extends StatefulWidget {
  const ProxyChainDialog(
      {super.key, required this.words, required this.initial});

  final Words words;
  final String initial;

  @override
  State<ProxyChainDialog> createState() => _ProxyChainDialogState();
}

class _ProxyChainDialogState extends State<ProxyChainDialog> {
  late final TextEditingController field;

  @override
  void initState() {
    super.initState();
    field = TextEditingController(text: widget.initial);
  }

  @override
  void dispose() {
    field.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: Text(widget.words.proxyChain),
        content: SizedBox(
          width: 380,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(widget.words.proxyChainDetail),
              const SizedBox(height: 12),
              TextField(
                controller: field,
                textDirection: TextDirection.ltr,
                minLines: 3,
                maxLines: 6,
                decoration:
                    InputDecoration(hintText: widget.words.proxyChainHint),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context),
              child: Text(widget.words.cancel)),
          TextButton(
              onPressed: () => Navigator.pop(context, field.text),
              child: Text(widget.words.save)),
        ],
      );
}

class ServerDialog extends StatefulWidget {
  const ServerDialog({
    super.key,
    required this.places,
    required this.selected,
    required this.words,
    required this.onSelect,
  });

  final List<Place> places;
  final String? selected;
  final Words words;
  final ValueChanged<Place> onSelect;

  @override
  State<ServerDialog> createState() => _ServerDialogState();
}

class _ServerDialogState extends State<ServerDialog> {
  String query = '';

  @override
  Widget build(BuildContext context) {
    final filtered = widget.places.where((place) {
      final value =
          '${place.code} ${place.country} ${place.city} ${place.label(widget.words.fa)}';
      return value.toLowerCase().contains(query.toLowerCase());
    }).toList();
    return AlertDialog(
      title: Text(widget.words.chooseServer),
      content: SizedBox(
        width: 360,
        height: 390,
        child: Column(
          children: [
            TextField(
              onChanged: (value) => setState(() => query = value.trim()),
              decoration: InputDecoration(
                hintText: widget.words.searchServers,
                prefixIcon: const Icon(Icons.search, color: soft),
              ),
            ),
            const SizedBox(height: 10),
            Expanded(
              child: filtered.isEmpty
                  ? Center(child: Text(widget.words.noServers))
                  : ListView.builder(
                      itemCount: filtered.length,
                      itemBuilder: (context, index) {
                        final place = filtered[index];
                        return ListTile(
                          leading: const Icon(Icons.public, color: soft),
                          title: Text(place.label(widget.words.fa)),
                          trailing: place.host == widget.selected
                              ? const Icon(Icons.check, color: soft)
                              : null,
                          onTap: () => widget.onSelect(place),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(widget.words.close)),
      ],
    );
  }
}

String _size(int value) {
  if (value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  final index =
      math.min((math.log(value) / math.ln2 / 10).floor(), units.length - 1);
  final number = value / math.pow(1024, index);
  return '${number.toStringAsFixed(index == 0 ? 0 : 1)} ${units[index]}';
}

String _speed(int bytes) => '${_size(math.max(0, bytes))}/s';
