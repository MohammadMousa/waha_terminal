import 'dart:async';

import 'package:flutter/foundation.dart';
import '../services/api_client.dart';
import '../services/local_prefs.dart';
import '../services/nfc_terminal_provider.dart';
import '../services/nfc_service.dart';
import '../services/terminal_provider.dart';
import '../services/usb_link_service.dart';

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

  // USB (device) mode: the kiosk hosts the link and owns session state, so
  // this app makes no backend calls and does no polling in this mode.
  UsbLinkService? _usbLink;
  StreamSubscription? _usbSub;
  String? _linkState;
  String? _linkDetail;
  String? _usbReference;
  Completer<void>? _usbCancel;

  bool get usbMode => LocalPrefs.connectionType == 'usb';

  TerminalStatus get status => _status;
  double? get amount => _amount;
  String? get currency => _currency;
  String? get errorMessage => _errorMessage;

  /// Human-readable USB link status for the idle screen; null outside USB mode.
  String? get linkStatusText {
    if (!usbMode) return null;
    switch (_linkState) {
      case 'ready':
        return 'Kiosk connected';
      case 'connected':
        return 'USB linked — handshaking…';
      case 'deviceAttached':
        return 'USB accessory attached…';
      case 'permissionRequested':
        return 'Waiting for USB permission…';
      case 'roleMismatch':
        return 'This phone is acting as the USB host. Swap roles (Settings → '
            'USB controlled by → connected device) or use a USB-A end on the kiosk side.';
      case 'error':
        return 'USB error: ${_linkDetail ?? 'unknown'}';
      default:
        return 'Waiting for the kiosk — connect the USB cable';
    }
  }

  // Rebuilds the provider from LocalPrefs.connectionType every call, so
  // switching NFC <-> USB in Settings and re-init()ing (see HomeScreen /
  // LoginScreen callers) takes effect without an app restart.
  Future<void> init() async {
    await _connectionSub?.cancel();
    await _usbSub?.cancel();
    _pollTimer?.cancel();
    _provider?.dispose();
    _provider = null;

    if (usbMode) {
      await _initUsbLink();
      return;
    }
    _provider = _createProvider();
    _connectionSub = _provider!.connectionEvents.listen((event) {
      if (event.detail != null) _lastConnectionError = event.detail;
    });
    _startPolling();
  }

  // 'bluetooth' has no real implementation yet (still a settings label
  // only) and falls back to NFC. USB is handled separately as the device
  // side of the kiosk-hosted link, see _initUsbLink.
  TerminalProvider _createProvider() => NfcTerminalProvider();

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

  // ── USB (device) mode ──────────────────────────────────────────────────────

  Future<void> _initUsbLink() async {
    _linkState = null;
    _linkDetail = null;
    _usbLink ??= UsbLinkService();
    _usbSub = _usbLink!.events.listen(_onLinkEvent);
    notifyListeners();
    await _usbLink!.connect();
  }

  void _onLinkEvent(UsbLinkEvent e) {
    switch (e.type) {
      case 'state':
        _linkState = e.state;
        _linkDetail = e.detail;
        notifyListeners();
      case 'paymentRequest':
        _onUsbPaymentRequest(
          e.reference!,
          e.data['amount'] as String,
          e.data['currency'] as String,
        );
      case 'cancel':
        // The native layer already answered the kiosk; just stop reading.
        if (_usbReference == e.reference) {
          _usbReference = null;
          _usbCancel?.complete();
          NfcService.cancel();
          _set(TerminalStatus.cancelled);
        }
      case 'linkClosed':
        _linkState = null;
        _linkDetail = null;
        if (_usbReference != null) {
          _usbReference = null;
          _usbCancel?.complete();
          NfcService.cancel();
          _errorMessage = 'USB link lost during payment';
          _set(TerminalStatus.error);
        } else {
          notifyListeners();
        }
    }
  }

  Future<void> _onUsbPaymentRequest(String reference, String amount, String currency) async {
    final link = _usbLink;
    if (link == null) return;
    _usbReference = reference;
    _amount = double.tryParse(amount);
    _currency = currency;
    _errorMessage = null;
    _set(TerminalStatus.pending);

    final nfc = NfcTerminalProvider();
    await nfc.connect();
    if (_usbReference != reference) return;
    if (!nfc.isConnected) {
      const msg = 'NFC is disabled or unsupported on this device';
      await _finishUsbPayment(reference, 'error',
          errorCode: 'NFC_UNAVAILABLE', message: msg, uiError: msg);
      return;
    }

    _set(TerminalStatus.reading);
    final cancel = _usbCancel = Completer<void>();
    final result = await Future.any<TerminalPaymentResponse?>([
      nfc.receiveResponse(),
      cancel.future.then((_) => null),
    ]);
    if (result == null || _usbReference != reference) return; // cancelled or link lost

    if (result.approved) {
      await _finishUsbPayment(reference, 'approved',
          approvalCode: result.authCode, details: result.notes);
    } else {
      final msg = result.errorMessage ?? 'Card read failed';
      final timedOut = msg.toLowerCase().contains('timeout');
      await _finishUsbPayment(reference, 'error',
          errorCode: timedOut ? 'TIMEOUT' : 'NFC_READ_FAILED', message: msg, uiError: msg);
    }
  }

  Future<void> _finishUsbPayment(
    String reference,
    String status, {
    String? approvalCode,
    String? errorCode,
    String? message,
    Map<String, Object>? details,
    String? uiError,
  }) async {
    final sent = await _usbLink!.sendPaymentResponse(
      reference: reference,
      status: status,
      approvalCode: approvalCode,
      errorCode: errorCode,
      message: message,
      details: details,
    );
    if (!sent) return; // no longer in flight (cancelled or link closed)
    _usbReference = null;
    if (status == 'approved') {
      _set(TerminalStatus.confirmed);
    } else {
      _errorMessage = uiError;
      _set(TerminalStatus.error);
    }
  }

  Future<void> cancel() async {
    final usbRef = _usbReference;
    if (usbMode && usbRef != null) {
      // Cashier abort in USB mode: answer the kiosk, stop the NFC read.
      _usbCancel?.complete();
      NfcService.cancel();
      await _usbLink?.sendPaymentResponse(
          reference: usbRef, status: 'cancelled', errorCode: 'CANCELLED');
      _usbReference = null;
      _set(TerminalStatus.idle);
      return;
    }
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
    _usbSub?.cancel();
    _provider?.dispose();
    super.dispose();
  }
}
