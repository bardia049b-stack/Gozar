import 'dart:async';
import 'dart:convert';
import 'dart:io';

class Place {
  const Place({
    required this.code,
    required this.country,
    required this.city,
    required this.host,
    required this.recommended,
    this.port = 0,
  });

  final String code;
  final String country;
  final String city;
  final String host;
  final bool recommended;
  final int port;

  factory Place.fromJson(Map<String, dynamic> json) => Place(
        code: json['code'] as String? ?? '',
        country: json['country'] as String? ?? '',
        city: json['city'] as String? ?? '',
        host: json['host'] as String? ?? '',
        recommended: json['recommended'] as bool? ?? false,
        port: (json['port'] as num?)?.toInt() ?? 0,
      );

  String label(bool fa) {
    if (recommended) return fa ? 'بهترین سرور' : 'Recommended server';
    if (country.isEmpty) return city;
    if (city.isEmpty || city == country) return country;
    return '$country · $city';
  }
}

class SavedAccount {
  const SavedAccount(
      {required this.id, required this.email, required this.active});

  final String id;
  final String email;
  final bool active;

  factory SavedAccount.fromJson(Map<String, dynamic> json) => SavedAccount(
        id: json['id'] as String? ?? '',
        email: json['email'] as String? ?? '',
        active: json['active'] as bool? ?? false,
      );
}

class GatewayState {
  const GatewayState({
    this.running = false,
    this.address = '',
    this.port = 0,
    this.clients = 0,
    this.total = 0,
    this.up = 0,
    this.down = 0,
    this.requires = false,
    this.allow = const [],
    this.dns = '',
  });

  final bool running;
  final String address;
  final int port;
  final int clients;
  final int total;
  final int up;
  final int down;
  final bool requires;
  final List<String> allow;
  final String dns;

  String get endpoint => address.isEmpty || port == 0 ? '' : '$address:$port';

  factory GatewayState.fromJson(Map<String, dynamic> json) {
    final allow = json['allow'] as List<dynamic>? ?? const [];
    return GatewayState(
      running: json['running'] as bool? ?? false,
      address: json['address'] as String? ?? '',
      port: (json['port'] as num?)?.toInt() ?? 0,
      clients: (json['clients'] as num?)?.toInt() ?? 0,
      total: (json['total'] as num?)?.toInt() ?? 0,
      up: (json['up'] as num?)?.toInt() ?? 0,
      down: (json['down'] as num?)?.toInt() ?? 0,
      requires: json['requiresAuth'] as bool? ?? false,
      allow: allow.whereType<String>().toList(growable: false),
      dns: json['dns'] as String? ?? '',
    );
  }
}

class CoreState {
  const CoreState({
    this.signedIn = false,
    this.email = '',
    this.language = '',
    this.theme = 'system',
    this.recents = false,
    this.chain = const [],
    this.accounts = const [],
    this.stage = '',
    this.live = false,
    this.connecting = false,
    this.mode = 'tun',
    this.fault = '',
    this.exit = '',
    this.device = '',
    this.needsAdmin = false,
    this.limit = 0,
    this.remaining = 0,
    this.rateDown = 0,
    this.rateUp = 0,
    this.down = 0,
    this.up = 0,
    this.version = '',
    this.server,
    this.servers = const [],
    this.udpRelay = true,
    this.udpPorts = const [],
    this.console = 'playstation',
    this.consoleOn = false,
    this.gateway = const GatewayState(),
  });

  final bool signedIn;
  final String email;
  final String language;
  final String theme;
  final bool recents;
  final List<String> chain;
  final List<SavedAccount> accounts;
  final String stage;
  final bool live;
  final bool connecting;
  final String mode;
  final String fault;
  final String exit;
  final String device;
  final bool needsAdmin;
  final int limit;
  final int remaining;
  final int rateDown;
  final int rateUp;
  final int down;
  final int up;
  final String version;
  final Place? server;
  final List<Place> servers;
  final bool udpRelay;
  final List<String> udpPorts;
  final String console;
  final bool consoleOn;
  final GatewayState gateway;

  factory CoreState.fromJson(Map<String, dynamic> json) {
    final rawServers = json['servers'] as List<dynamic>? ?? const [];
    final rawServer = json['server'];
    final rawAccounts = json['accounts'] as List<dynamic>? ?? const [];
    final rawChain = json['chain'] as List<dynamic>? ?? const [];
    return CoreState(
      signedIn: json['signed_in'] as bool? ?? false,
      email: json['email'] as String? ?? '',
      language: json['language'] as String? ?? '',
      theme: json['theme'] as String? ?? 'system',
      recents: json['recents'] as bool? ?? false,
      chain: rawChain.whereType<String>().toList(growable: false),
      accounts: rawAccounts
          .whereType<Map<String, dynamic>>()
          .map(SavedAccount.fromJson)
          .toList(growable: false),
      stage: json['stage'] as String? ?? '',
      live: json['live'] as bool? ?? false,
      connecting: json['connecting'] as bool? ?? false,
      mode: json['mode'] as String? ?? 'tun',
      fault: json['fault'] as String? ?? '',
      exit: json['exit'] as String? ?? '',
      device: json['device'] as String? ?? '',
      needsAdmin: json['needs_admin'] as bool? ?? false,
      limit: (json['limit'] as num?)?.toInt() ?? 0,
      remaining: (json['remaining'] as num?)?.toInt() ?? 0,
      rateDown: (json['rate_down'] as num?)?.toInt() ?? 0,
      rateUp: (json['rate_up'] as num?)?.toInt() ?? 0,
      down: (json['down'] as num?)?.toInt() ?? 0,
      up: (json['up'] as num?)?.toInt() ?? 0,
      version: json['version'] as String? ?? '',
      server:
          rawServer is Map<String, dynamic> ? Place.fromJson(rawServer) : null,
      udpRelay: json['udp_relay'] as bool? ?? true,
      udpPorts: (json['udp_ports'] as List<dynamic>? ?? const [])
          .whereType<String>()
          .toList(growable: false),
      console: json['console'] as String? ?? 'playstation',
      consoleOn: json['console_on'] as bool? ?? false,
      gateway: json['gateway'] is Map<String, dynamic>
          ? GatewayState.fromJson(json['gateway'] as Map<String, dynamic>)
          : const GatewayState(),
      servers: rawServers
          .whereType<Map<String, dynamic>>()
          .map(Place.fromJson)
          .toList(growable: false),
    );
  }
}

class CoreReply {
  const CoreReply({
    this.state,
    this.error,
    this.needsCode = false,
    this.captchaImage,
    this.systemEmails = const [],
  });

  final CoreState? state;
  final String? error;
  final bool needsCode;
  final String? captchaImage;
  final List<String> systemEmails;

  factory CoreReply.fromJson(Map<String, dynamic> json) {
    final state = json['state'];
    final result = json['reply'];
    final error = json['error'];
    return CoreReply(
      state: state is Map<String, dynamic> ? CoreState.fromJson(state) : null,
      error: error is String && error.isNotEmpty ? error : null,
      needsCode: result is Map && result['needs_code'] == true,
      systemEmails: result is Map && result['emails'] is List
          ? (result['emails'] as List)
              .whereType<String>()
              .toList(growable: false)
          : const [],
      captchaImage: result is Map && result['captcha_image'] is String
          ? result['captcha_image'] as String
          : null,
    );
  }
}

class Core {
  Process? _process;
  int _nextId = 0;
  bool _stopping = false;
  final Map<int, Completer<CoreReply>> _pending = {};
  void Function()? onExit;

  bool get running => _process != null;

  String? _findBinary() {
    final override = Platform.environment['GOZAR_CORE'];
    if (override != null &&
        override.isNotEmpty &&
        File(override).existsSync()) {
      return override;
    }
    final name = Platform.isWindows ? 'gozar-core.exe' : 'gozar-core';
    final parent = File(Platform.resolvedExecutable).parent.path;
    final candidate = '$parent${Platform.pathSeparator}$name';
    return File(candidate).existsSync() ? candidate : null;
  }

  Future<void> start() async {
    if (_process != null) return;
    final path = _findBinary();
    if (path == null) throw StateError('core not found');
    final process = await Process.start(path, const ['--rpc']);
    _process = process;
    process.stdout
        .transform(utf8.decoder)
        .transform(const LineSplitter())
        .listen(_receive);
    process.stderr.listen((_) {});
    unawaited(process.exitCode.then((_) {
      if (_process == process) _process = null;
      for (final waiter in _pending.values) {
        if (!waiter.isCompleted) {
          waiter.completeError(StateError('core disconnected'));
        }
      }
      _pending.clear();
      if (!_stopping) onExit?.call();
    }));
  }

  void _receive(String line) {
    try {
      final decoded = jsonDecode(line);
      if (decoded is! Map<String, dynamic>) return;
      final id = decoded['id'];
      if (id is! int) return;
      final waiter = _pending.remove(id);
      if (waiter != null && !waiter.isCompleted) {
        waiter.complete(CoreReply.fromJson(decoded));
      }
    } on FormatException {
      return;
    }
  }

  Future<CoreReply> call(String method, [Map<String, dynamic>? params]) async {
    final process = _process;
    if (process == null || _stopping) {
      throw StateError('core not running');
    }
    final id = ++_nextId;
    final waiter = Completer<CoreReply>();
    _pending[id] = waiter;
    final message = <String, dynamic>{'id': id, 'method': method};
    if (params != null) message['params'] = params;
    try {
      process.stdin.writeln(jsonEncode(message));
      await process.stdin.flush();
      return await waiter.future.timeout(const Duration(seconds: 180));
    } finally {
      _pending.remove(id);
    }
  }

  Future<void> shutdown() async {
    final process = _process;
    stop();
    if (process == null) return;
    try {
      await process.exitCode.timeout(const Duration(seconds: 10));
    } on TimeoutException {
      return;
    }
  }

  void stop() {
    final process = _process;
    if (process == null || _stopping) return;
    _stopping = true;
    try {
      process.stdin.writeln(jsonEncode({'id': ++_nextId, 'method': 'quit'}));
      unawaited(process.stdin.close());
    } on Object {
      return;
    }
  }
}
