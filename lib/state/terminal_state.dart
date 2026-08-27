import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';
import '../services/api_client.dart';
import '../services/local_prefs.dart';
import '../services/nfc_service.dart';

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
  NfcAvailability _nfcAvailability = NfcAvailability.available;

  TerminalStatus get status => _status;
  double? get amount => _amount;
  String? get currency => _currency;
  String? get errorMessage => _errorMessage;
  NfcAvailability get nfcAvailability => _nfcAvailability;

  Future<void> init() async {
    _nfcAvailability = await NfcService.checkAvailability();
    notifyListeners();
    _startPolling();
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
          _startNfcRead();
        }
      } catch (_) {}
    }
  }

  Future<void> _startNfcRead() async {
    if (_nfcAvailability != NfcAvailability.available) {
      _errorMessage = _nfcAvailability == NfcAvailability.disabled
          ? 'NFC is disabled — please enable it in device settings'
          : 'This device does not support NFC';
      _set(TerminalStatus.error);
      return;
    }

    _set(TerminalStatus.reading);
    try {
      final card = await NfcService.readCard();
      final authCode = _generateAuthCode();
      final fullDebug = LocalPrefs.debugLevel == 'full';
      final notes = card.toNotes(full: fullDebug);

      await _api.confirmSession(
        _sessionId!,
        authCode: authCode,
        notes: notes,
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

  // Generates a 6-digit mock auth code. Real auth code comes from gateway in production.
  String _generateAuthCode() =>
      Random().nextInt(900000).toString().padLeft(6, '0');

  void _set(TerminalStatus s) {
    _status = s;
    notifyListeners();
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }
}
