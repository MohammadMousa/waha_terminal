import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'terminal_provider.dart';

/// Generic Android USB host transport — internal name `waha-terminal` per
/// the USB Payment Transport Evaluation doc. This is NOT a Geidea/PAX
/// implementation and must never be presented as protocol-compatible with
/// either. It exists to exercise the same class of USB lifecycle bugs the
/// Geidea integration hits — stale cached connection state, permission /
/// registerReceiver plumbing, reconnect-after-unplug — against any attached
/// USB peripheral, without needing Geidea hardware. See
/// `UsbTerminalManager.kt` for the native side; it deliberately never trusts
/// a cached "connected" boolean across a lifecycle gap, re-deriving it live
/// on every check instead.
///
/// [requestPayment]/[receiveResponse] need a peripheral with bulk IN/OUT
/// endpoints (e.g. a USB-serial/FTDI/CP210x adapter) to exercise past the
/// permission/connect stage — a device without one (e.g. plain USB storage)
/// will connect fine but fail those two calls with a clear error naming why.
class UsbTerminalProvider implements TerminalProvider {
  static const _methodChannel = MethodChannel('com.waha.waha_terminal/usb_terminal');
  static const _eventChannel = EventChannel('com.waha.waha_terminal/usb_terminal/events');

  /// Bypasses requestPayment/receiveResponse only — connect/disconnect stay
  /// real — so the app's own state machine (pending → reading → confirmed →
  /// backend confirm) can be exercised end to end without a peripheral that
  /// speaks this transport's made-up payload format (nothing does; that's
  /// by design, see the class doc). Same pattern and same reasoning as
  /// GeideaTerminalBridge.fakeTerminalEnabled in waha_platform: in-memory
  /// only, never persisted, always resets to false on a fresh launch so a
  /// terminal can't be left silently faking payments after a restart.
  static bool mockPaymentEnabled = false;
  static bool get _mockPayment =>
      const bool.fromEnvironment('USB_MOCK_PAYMENT') || mockPaymentEnabled;

  final _connectionController = StreamController<TerminalConnectionEvent>.broadcast();
  StreamSubscription? _eventSub;
  bool _connected = false;

  UsbTerminalProvider() {
    _eventSub = _eventChannel.receiveBroadcastStream().listen((event) {
      final map = Map<String, dynamic>.from(event as Map);
      final state = _stateFromName(map['state'] as String?);
      _connected = state == TerminalConnectionState.connected;
      _connectionController.add(TerminalConnectionEvent(state, detail: map['detail'] as String?));
    });
  }

  TerminalConnectionState _stateFromName(String? name) {
    switch (name) {
      case 'deviceAttached':       return TerminalConnectionState.deviceAttached;
      case 'permissionRequested':  return TerminalConnectionState.permissionRequested;
      case 'permissionDenied':     return TerminalConnectionState.permissionDenied;
      case 'connected':            return TerminalConnectionState.connected;
      case 'disconnected':         return TerminalConnectionState.disconnected;
      case 'error':                return TerminalConnectionState.error;
      default:                     return TerminalConnectionState.disconnected;
    }
  }

  @override
  Stream<TerminalConnectionEvent> get connectionEvents => _connectionController.stream;

  @override
  Future<void> connect() async {
    // Fires the native connect attempt and returns immediately — actual
    // outcome (permission dialog, device open) arrives asynchronously via
    // connectionEvents, so [isConnected] is only meaningful after that.
    try {
      await _methodChannel.invokeMethod('connect');
    } on PlatformException catch (e) {
      _connected = false;
      _connectionController.add(TerminalConnectionEvent(
        TerminalConnectionState.error,
        detail: e.message ?? 'USB connect failed',
      ));
    }
    // Give the (usually near-instant, already-permitted-device) native
    // round trip a brief window to report back before callers check
    // isConnected — a genuine permission prompt still resolves later via
    // the event stream, same as any other async connect result.
    await Future<void>.delayed(const Duration(milliseconds: 300));
  }

  @override
  Future<void> disconnect() async {
    try {
      await _methodChannel.invokeMethod('disconnect');
    } on PlatformException catch (_) {
      // Best-effort — nothing more useful to do if this fails.
    }
    _connected = false;
  }

  @override
  bool get isConnected => _connected;

  @override
  bool get isIdle => _connected;

  @override
  Future<void> requestPayment({required double amount, required String reference}) async {
    if (_mockPayment) return;
    final result = await _methodChannel.invokeMethod<Map>('requestPayment', {
      'amount': amount,
      'reference': reference,
    });
    final ok = result?['ok'] == true;
    if (!ok) {
      throw Exception(result?['error'] as String? ?? 'USB payment request failed');
    }
  }

  @override
  Future<TerminalPaymentResponse> receiveResponse({Duration timeout = const Duration(seconds: 90)}) async {
    if (_mockPayment) {
      await Future<void>.delayed(const Duration(seconds: 2));
      debugPrint('[WahaTerminal] Payment completed (mocked — no peripheral involved)');
      return const TerminalPaymentResponse(
        approved: true,
        authCode: 'USB-MOCK',
        notes: {'mock': true},
      );
    }
    try {
      final result = await _methodChannel.invokeMethod<Map>('receiveResponse', {
        'timeoutMs': timeout.inMilliseconds,
      });
      final data = result?['data'] as String?;
      final error = result?['error'] as String?;
      if (error != null || data == null) {
        return TerminalPaymentResponse(
          approved: false,
          errorMessage: error ?? 'No response from USB device',
        );
      }
      // Generic byte echo only — no vendor protocol is parsed or assumed.
      // This proves the transport round-trips bytes, nothing more.
      debugPrint('[WahaTerminal] Payment completed');
      return TerminalPaymentResponse(
        approved: true,
        authCode: 'USB-ECHO',
        notes: {'raw_response': data},
      );
    } on PlatformException catch (e) {
      return TerminalPaymentResponse(approved: false, errorMessage: e.message ?? 'USB error');
    }
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    _connectionController.close();
  }
}
