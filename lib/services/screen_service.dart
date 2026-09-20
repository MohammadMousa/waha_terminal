import 'package:flutter/services.dart';
import 'local_prefs.dart';

/// Keeps the display on while the app is in the foreground, so NFC reading and
/// the amount shown to the customer are not lost to the screen timeout.
class ScreenService {
  ScreenService._();
  static const _channel = MethodChannel('com.waha.waha_terminal/screen');

  /// Applies the saved "Keep screen on" preference.
  static Future<void> apply() => set(LocalPrefs.keepScreenOn);

  static Future<void> set(bool on) async {
    try {
      await _channel.invokeMethod('setKeepScreenOn', {'on': on});
    } on PlatformException {
      // Best-effort — the screen just follows the system timeout.
    } on MissingPluginException {
      // Not running on the Android host (e.g. tests).
    }
  }
}
