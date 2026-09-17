import 'dart:async';
import 'dart:math';

import 'local_prefs.dart';
import 'nfc_service.dart';
import 'terminal_provider.dart';

/// Wraps the existing NFC flow behind [TerminalProvider] so [TerminalState]
/// can treat NFC and USB uniformly. Behavior matches the pre-abstraction
/// code exactly — same NfcService calls, same synthetic auth code.
class NfcTerminalProvider implements TerminalProvider {
  final _connectionController = StreamController<TerminalConnectionEvent>.broadcast();
  bool _connected = false;

  @override
  Stream<TerminalConnectionEvent> get connectionEvents => _connectionController.stream;

  @override
  Future<void> connect() async {
    final availability = await NfcService.checkAvailability();
    _connected = availability == NfcAvailability.available;
    _connectionController.add(TerminalConnectionEvent(
      _connected ? TerminalConnectionState.connected : TerminalConnectionState.error,
      detail: _connected
          ? null
          : (availability == NfcAvailability.disabled
              ? 'NFC is disabled — please enable it in device settings'
              : 'This device does not support NFC'),
    ));
  }

  @override
  Future<void> disconnect() async {
    _connected = false;
    _connectionController.add(const TerminalConnectionEvent(TerminalConnectionState.disconnected));
  }

  @override
  bool get isConnected => _connected;

  // NFC has no persistent session to be "busy" outside of an active read.
  @override
  bool get isIdle => true;

  @override
  Future<void> requestPayment({required double amount, required String reference}) async {
    // Nothing to send ahead of time — the card is read on receiveResponse().
  }

  @override
  Future<TerminalPaymentResponse> receiveResponse({Duration timeout = const Duration(seconds: 90)}) async {
    try {
      final card = await NfcService.readCard(timeout: timeout);
      final fullDebug = LocalPrefs.debugLevel == 'full';
      return TerminalPaymentResponse(
        approved: true,
        authCode: _generateAuthCode(),
        notes: card.toNotes(full: fullDebug),
      );
    } on Exception catch (e) {
      return TerminalPaymentResponse(
        approved: false,
        errorMessage: e.toString().replaceFirst('Exception: ', ''),
      );
    }
  }

  // Generates a 6-digit mock auth code. Real auth code comes from gateway in production.
  String _generateAuthCode() => Random().nextInt(900000).toString().padLeft(6, '0');

  @override
  void dispose() {
    _connectionController.close();
  }
}
