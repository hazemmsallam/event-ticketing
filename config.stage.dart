import 'dart:math';

import 'package:shared_preferences/shared_preferences.dart';

/// App-wide configuration and the persisted per-device customer reference.
class Config {
  Config._();

  /// Base URL of the event-ticketing API.
  ///
  /// - Windows/desktop, web, iOS simulator: use localhost (default)
  /// - Android emulator reaches the host machine at 10.0.2.2 instead
  /// - A physical device needs your machine's LAN IP (e.g. http://192.168.1.20:8080)
  static const String baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'https://uncounted-freehand-appetite.ngrok-free.dev',
  );

  static const _customerRefKey = 'customerRef';
  static String? _customerRef;

  /// The X-Customer-Ref sent with booking calls. Generated once per install and
  /// persisted so booking history survives app restarts.
  static String get customerRef => _customerRef ?? 'anonymous';

  static Future<void> init() async {
    final prefs = await SharedPreferences.getInstance();
    var ref = prefs.getString(_customerRefKey);
    if (ref == null || ref.isEmpty) {
      ref = _generateRef();
      await prefs.setString(_customerRefKey, ref);
    }
    _customerRef = ref;
  }

  static String _generateRef() {
    final rnd = Random();
    final suffix = List.generate(8, (_) => rnd.nextInt(16).toRadixString(16)).join();
    return 'mobile-${DateTime.now().millisecondsSinceEpoch}-$suffix';
  }
}
