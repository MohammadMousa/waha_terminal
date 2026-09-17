import 'dart:async';

enum TerminalConnectionState {
  disconnected,
  deviceAttached,
  permissionRequested,
  permissionDenied,
  connected,
  error,
}

class TerminalConnectionEvent {
  final TerminalConnectionState state;
  final String? detail;
  const TerminalConnectionEvent(this.state, {this.detail});
}

/// Transport-agnostic result of one payment attempt. [authCode] is a
/// synthetic/echoed value from whichever transport produced it — never a
/// real gateway authorization code. Card/device detail goes in [notes].
class TerminalPaymentResponse {
  final bool approved;
  final String? authCode;
  final Map<String, Object> notes;
  final String? errorMessage;

  const TerminalPaymentResponse({
    required this.approved,
    this.authCode,
    this.notes = const {},
    this.errorMessage,
  });
}

/// Common shape every card-present transport (NFC today, USB as of this
/// change) implements, so [TerminalState] can drive the payment session
/// lifecycle — poll, pending, request, response, confirm/cancel — without
/// knowing which physical transport is underneath. Only the transport-
/// specific communication should differ between implementations; session
/// polling and backend confirm/cancel stay in TerminalState.
abstract class TerminalProvider {
  Stream<TerminalConnectionEvent> get connectionEvents;

  Future<void> connect();
  Future<void> disconnect();
  bool get isConnected;
  bool get isIdle;

  Future<void> requestPayment({required double amount, required String reference});
  Future<TerminalPaymentResponse> receiveResponse({Duration timeout = const Duration(seconds: 90)});

  void dispose();
}
