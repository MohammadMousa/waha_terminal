import 'dart:convert';
import 'package:http/http.dart' as http;
import 'local_prefs.dart';

class ApiClient {
  String get _base =>
      LocalPrefs.apiBaseUrl ?? LocalPrefs.defaultApiBaseUrl;

  Map<String, String> _headers() => {
        'Content-Type': 'application/json',
        if (LocalPrefs.authToken != null)
          'Authorization': 'Bearer ${LocalPrefs.authToken}',
      };

  // ── Auth ────────────────────────────────────────────────────────────────────

  Future<String> login(String username, String password) async {
    final resp = await http
        .post(
          Uri.parse('$_base/api/auth/login'),
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode({'username': username, 'password': password}),
        )
        .timeout(const Duration(seconds: 10));
    if (resp.statusCode == 200) {
      final body = jsonDecode(resp.body) as Map<String, dynamic>;
      return body['token'] as String;
    }
    if (resp.statusCode == 401) throw UnauthorizedException();
    throw Exception('Login failed: ${resp.statusCode}');
  }

  /// Returns true if the cached token is still valid.
  Future<bool> validateToken() async {
    final token = LocalPrefs.authToken;
    if (token == null) return false;
    try {
      final resp = await http
          .get(
            Uri.parse('$_base/api/auth/me'),
            headers: _headers(),
          )
          .timeout(const Duration(seconds: 5));
      return resp.statusCode == 200;
    } catch (_) {
      // Network unreachable — assume token is still valid, try again next boot.
      return true;
    }
  }

  // ── Terminal sessions ────────────────────────────────────────────────────────

  Future<Map<String, dynamic>?> getPendingSession() async {
    final resp = await http
        .get(Uri.parse('$_base/api/terminal-sessions/pending'), headers: _headers())
        .timeout(const Duration(seconds: 5));
    if (resp.statusCode == 204) return null;
    if (resp.statusCode == 200) return jsonDecode(resp.body) as Map<String, dynamic>;
    throw Exception('GET pending failed: ${resp.statusCode}');
  }

  Future<Map<String, dynamic>> getSession(String id) async {
    final resp = await http
        .get(Uri.parse('$_base/api/terminal-sessions/$id'), headers: _headers())
        .timeout(const Duration(seconds: 5));
    if (resp.statusCode == 200) return jsonDecode(resp.body) as Map<String, dynamic>;
    throw Exception('GET session failed: ${resp.statusCode}');
  }

  Future<void> confirmSession(String id, {
    required String authCode,
    required Map<String, Object> notes,
  }) async {
    final resp = await http
        .post(
          Uri.parse('$_base/api/terminal-sessions/$id/confirm'),
          headers: _headers(),
          body: jsonEncode({'authCode': authCode, 'notes': notes}),
        )
        .timeout(const Duration(seconds: 5));
    if (resp.statusCode != 200) throw Exception('Confirm failed: ${resp.statusCode}');
  }

  Future<void> cancelSession(String id) async {
    await http
        .post(
          Uri.parse('$_base/api/terminal-sessions/$id/cancel'),
          headers: _headers(),
        )
        .timeout(const Duration(seconds: 5));
  }
}

class UnauthorizedException implements Exception {}
