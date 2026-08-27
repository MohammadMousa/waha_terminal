import 'package:shared_preferences/shared_preferences.dart';

class LocalPrefs {
  LocalPrefs._();
  static late SharedPreferences _p;

  static Future<void> init() async {
    _p = await SharedPreferences.getInstance();
  }

  // ── Connection ─────────────────────────────────────────────────────────────

  static const _kCustomUrl   = 'waha.terminal.custom_url_enabled';
  static const _kApiBaseUrl  = 'waha.terminal.api_base_url';
  static const _kConnType    = 'waha.terminal.connection_type';
  static const _kDebugLevel  = 'waha.terminal.debug_level';

  static const defaultApiBaseUrl = 'http://10.0.2.2:8081';

  static bool get customUrlEnabled => _p.getBool(_kCustomUrl) ?? false;
  static Future<void> setCustomUrlEnabled(bool v) => _p.setBool(_kCustomUrl, v);

  /// Null when custom URL is disabled — callers should fall back to [defaultApiBaseUrl].
  static String? get apiBaseUrl => customUrlEnabled ? _p.getString(_kApiBaseUrl) : null;
  static Future<void> setApiBaseUrl(String v) => _p.setString(_kApiBaseUrl, v);

  // 'same_device' | 'usb' | 'bluetooth'
  static String get connectionType => _p.getString(_kConnType) ?? 'same_device';
  static Future<void> setConnectionType(String v) => _p.setString(_kConnType, v);

  // 'minimal' | 'full'
  static String get debugLevel => _p.getString(_kDebugLevel) ?? 'minimal';
  static Future<void> setDebugLevel(String v) => _p.setString(_kDebugLevel, v);

  // ── Auth ───────────────────────────────────────────────────────────────────

  static const _kAuthToken    = 'waha.terminal.auth_token';
  static const _kAuthUsername = 'waha.terminal.auth_username';
  static const _kAuthPassword = 'waha.terminal.auth_password';

  static String? get authToken    => _p.getString(_kAuthToken);
  static String? get authUsername => _p.getString(_kAuthUsername);
  static String? get authPassword => _p.getString(_kAuthPassword);

  static Future<void> setAuthToken(String v)    => _p.setString(_kAuthToken, v);
  static Future<void> setAuthCredentials(String u, String pw) async {
    await _p.setString(_kAuthUsername, u);
    await _p.setString(_kAuthPassword, pw);
  }
  static Future<void> clearAuth() async {
    await _p.remove(_kAuthToken);
    await _p.remove(_kAuthUsername);
    await _p.remove(_kAuthPassword);
  }
}
