import 'api_client.dart';
import 'local_prefs.dart';

class TerminalAuthService {
  TerminalAuthService._();

  /// Startup auth:
  ///   1. Cached token valid → done.
  ///   2. Token invalid / 401 → re-login with cached credentials.
  ///   3. No credentials → caller shows LoginScreen.
  /// Returns true if authenticated after the call.
  static Future<bool> resolveStartupAuth(ApiClient api) async {
    if (LocalPrefs.authToken != null) {
      final valid = await api.validateToken();
      if (valid) return true;

      // Token expired — try to re-login silently.
      final u = LocalPrefs.authUsername;
      final p = LocalPrefs.authPassword;
      if (u != null && p != null) {
        try {
          final token = await api.login(u, p);
          await LocalPrefs.setAuthToken(token);
          return true;
        } catch (_) {
          await LocalPrefs.clearAuth();
          return false;
        }
      }

      await LocalPrefs.clearAuth();
      return false;
    }

    return false;
  }

  static Future<void> login(ApiClient api, String username, String password) async {
    final token = await api.login(username, password);
    await LocalPrefs.setAuthToken(token);
    await LocalPrefs.setAuthCredentials(username, password);
  }

  static Future<void> logout() async {
    await LocalPrefs.clearAuth();
  }
}
