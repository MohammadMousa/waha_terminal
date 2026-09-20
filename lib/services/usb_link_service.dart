import 'dart:async';
import 'package:flutter/services.dart';

class UsbLinkEvent {
  final String type; // state | paymentRequest | cancel | linkClosed
  final Map<String, dynamic> data;
  const UsbLinkEvent(this.type, this.data);

  String? get state => data['state'] as String?;
  String? get detail => data['detail'] as String?;
  String? get reference => data['reference'] as String?;
}

/// Dart face of the native device-side (accessory) link. The kiosk is the USB
/// host and drives the protocol; this only surfaces its events and returns
/// the payment answer. All framing lives natively in AccessoryLink.
class UsbLinkService {
  static const _method = MethodChannel('com.waha.waha_terminal/usb_link');
  static const _eventChannel = EventChannel('com.waha.waha_terminal/usb_link/events');

  Stream<UsbLinkEvent>? _events;

  Stream<UsbLinkEvent> get events => _events ??= _eventChannel
      .receiveBroadcastStream()
      .map((e) {
        final map = Map<String, dynamic>.from(e as Map);
        return UsbLinkEvent(map['type'] as String, map);
      })
      .asBroadcastStream();

  /// Live native status: ready | connected | attached | roleMismatch | none.
  Future<String> linkStatus() async {
    try {
      return await _method.invokeMethod<String>('linkStatus') ?? 'none';
    } on PlatformException {
      return 'none';
    }
  }

  /// Tries to open an already-attached accessory. Outcome arrives as events.
  Future<void> connect() async {
    try {
      await _method.invokeMethod('connect');
    } on PlatformException {
      // Best-effort — failures are reported through the event stream.
    }
  }

  /// Live native check, never a cached flag.
  Future<bool> isLinkOpen() async {
    try {
      return await _method.invokeMethod<bool>('isLinkOpen') ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// False means the payment was no longer in flight (already cancelled or
  /// the link closed) and nothing was sent.
  Future<bool> sendPaymentResponse({
    required String reference,
    required String status,
    String? approvalCode,
    String? errorCode,
    String? message,
    Map<String, Object>? details,
  }) async {
    try {
      return await _method.invokeMethod<bool>('sendPaymentResponse', {
            'reference': reference,
            'status': status,
            'approvalCode': approvalCode,
            'errorCode': errorCode,
            'message': message,
            'details': details,
          }) ??
          false;
    } on PlatformException {
      return false;
    }
  }
}
