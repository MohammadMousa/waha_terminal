import 'dart:async';

import 'package:flutter/foundation.dart';
import '../services/api_client.dart';
import '../services/local_prefs.dart';
import '../services/nfc_terminal_provider.dart';
import '../services/terminal_provider.dart';
import '../services/usb_terminal_provider.dart';

enum TerminalStatus { idle, pending, reading, confirmed, cancelled, timeout, error }

class TerminalState extends ChangeNotifier {
  final ApiClient _api;

  TerminalState(this._api);

  TerminalStatus _status = TerminalStatus.idle;
  String? _sessionId;
  double? _amount;
  String? _currency;
  String? _errorMessage;
  Timer? _pollTimer;
  TerminalProvider? _provider;
  StreamSubscription? _connectionSub;
  String? _lastConnectionError;

  TerminalStatus get status => _status;
  double? get amount => _amount;
  String? get currency => _currency;
  String? get errorMessage => _errorMessage;

  // Rebuilds the provider from LocalPrefs.connectionType every call, so
  // switching NFC <-> USB in Settings and re-init()ing (see HomeScreen /
  // LoginScreen callers) takes effect without an app restart.
  Future<void> init() async {
    await _connectionSub?.cancel();
    _provider?.dispose();
    _provider = _createProvider();
    _connectionSub = _provider!.connectionEvents.listen((event) {
      if (event.detail != null) _lastConnectionError = event.detail;
    });
    _startPolling();
  }

  TerminalProvider _createProvider() {
    // 'bluetooth' has no real implementation yet (still a settings label
    // only, per the Connection Type screen) — falls back to NFC, matching
    // pre-abstraction behavior where connectionType had no runtime effect
    // beyond 'usb'.
    return LocalPrefs.connectionType == 'usb' ? UsbTerminalProvider() : NfcTerminalProvider();
  }

  void _startPolling() {
    _pollTimer?.cancel();
    _pollTimer = Timer.periodic(const Duration(seconds: 2), (_) => _poll());
  }

  Future<void> _poll() async {
    // Track in-progress session status changes.
    if (_sessionId != null &&
        (_status == TerminalStatus.pending || _status == TerminalStatus.reading)) {
      try {
        final data = await _api.getSession(_sessionId!);
        final serverStatus = data['status'] as String;
        if (serverStatus == 'TIMEOUT') {
          _sessionId = null;
          _set(TerminalStatus.timeout);
        } else if (serverStatus == 'CANCELLED') {
          _sessionId = null;
          _set(TerminalStatus.cancelled);
        }
      } catch (_) {}
      return;
    }

    // Idle — look for a new pending session. No store filter needed.
    if (_status == TerminalStatus.idle) {
      try {
        final data = await _api.getPendingSession();
        if (data != null) {
          _sessionId = data['id'] as String;
          _amount = (data['amount'] as num).toDouble();
          _currency = data['currency'] as String;
          _set(TerminalStatus.pending);
          _runTransport();
        }
      } catch (_) {}
    }
  }

  Future<void> _runTransport() async {
    final provider = _provider;
    if (provider == null) return; // init() hasn't run yet — nothing to drive.

    if (!provider.isConnected) {
      await provider.connect();
    }
    if (!provider.isConnected) {
      _errorMessage = _lastConnectionError ?? 'Terminal not connected';
      _sessionId = null;
      _set(TerminalStatus.error);
      return;
    }

    _set(TerminalStatus.reading);
    try {
      await provider.requestPayment(amount: _amount!, reference: _sessionId!);
      final response = await provider.receiveResponse();
      if (!response.approved) {
        _errorMessage = response.errorMessage ?? 'Payment failed';
        _sessionId = null;
        _set(TerminalStatus.error);
        return;
      }
      await _api.confirmSession(
        _sessionId!,
        authCode: response.authCode ?? '000000',
        notes: response.notes,
      );
      _sessionId = null;
      _set(TerminalStatus.confirmed);
    } on Exception catch (e) {
      _errorMessage = e.toString().replaceFirst('Exception: ', '');
      _sessionId = null;
      _set(TerminalStatus.error);
    }
  }

  Future<void> cancel() async {
    if (_sessionId != null) {
      try { await _api.cancelSession(_sessionId!); } catch (_) {}
    }
    _sessionId = null;
    _set(TerminalStatus.idle);
  }

  void dismissResult() {
    _errorMessage = null;
    _set(TerminalStatus.idle);
  }

  void _set(TerminalStatus s) {
    _status = s;
    notifyListeners();
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _connectionSub?.cancel();
    _provider?.dispose();
    super.dispose();
  }
}
