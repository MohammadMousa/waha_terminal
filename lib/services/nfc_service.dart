import 'package:flutter/services.dart';
import 'package:flutter_nfc_kit/flutter_nfc_kit.dart';

enum NfcAvailability { available, disabled, notSupported }

// EMV card data extracted from NFC APDU exchange.
class EmvCardData {
  final String uid;           // NFC tag UID (always present)
  final String? brand;        // VISA / MASTERCARD / AMEX / UNKNOWN
  final String? last4;        // masked PAN last 4 digits
  final String? expiry;       // MM/YYYY
  final String? appLabel;     // e.g. "VISA CREDIT"

  const EmvCardData({
    required this.uid,
    this.brand,
    this.last4,
    this.expiry,
    this.appLabel,
  });

  Map<String, Object> toNotes({required bool full}) {
    final m = <String, Object>{
      'card_uid': uid,
      if (brand != null) 'brand': brand!,
      if (last4 != null) 'last4': last4!,
    };
    if (full) {
      if (expiry != null) m['expiry'] = expiry!;
      if (appLabel != null) m['app_label'] = appLabel!;
      m['entry_method'] = 'CONTACTLESS';
    }
    return m;
  }
}

class NfcService {
  NfcService._();

  static Future<NfcAvailability> checkAvailability() async {
    try {
      final avail = await FlutterNfcKit.nfcAvailability;
      switch (avail) {
        case NFCAvailability.available:    return NfcAvailability.available;
        case NFCAvailability.disabled:     return NfcAvailability.disabled;
        case NFCAvailability.not_supported: return NfcAvailability.notSupported;
      }
    } catch (_) {
      return NfcAvailability.notSupported;
    }
  }

  // Polls for one NFC tag, reads EMV data via APDU, returns card data.
  static Future<EmvCardData> readCard({Duration timeout = const Duration(seconds: 90)}) async {
    try {
      final tag = await FlutterNfcKit.poll(
        timeout: timeout,
        iosAlertMessage: 'Hold your card near the device',
      );
      final uid = tag.id;

      // Only ISO 14443-4 cards support APDU (payment cards).
      if (tag.type != NFCTagType.iso7816) {
        return EmvCardData(uid: uid);
      }

      // 1. SELECT PPSE — wakes the card's payment application layer
      await FlutterNfcKit.transceive('00A4040007A0000000041010');

      // 2. SELECT PPSE proper
      final ppse = await FlutterNfcKit.transceive(
        '00A4040E325041592E5359532E4444463031', // SELECT 2PAY.SYS.DDF01
      );
      final aid = _extractFirstAid(ppse);
      if (aid == null) return EmvCardData(uid: uid);

      // 3. SELECT application by AID
      final selectApp = await FlutterNfcKit.transceive('00A40400${_hexLen(aid)}${aid}00');
      final appLabel = _extractTag(selectApp, '50');
      final brand = _brandFromAid(aid);

      // 4. GET PROCESSING OPTIONS — initiates transaction context on card
      await FlutterNfcKit.transceive('80A8000002830000');

      // 5. READ RECORD SFI 1, Record 1 — contains PAN, expiry
      final record = await FlutterNfcKit.transceive('00B2010C00');

      final track2 = _extractTag(record, '57');
      final last4 = _last4FromTrack2(track2);
      final expiry = _expiryFromTrack2(track2);

      return EmvCardData(
        uid: uid,
        brand: brand,
        last4: last4,
        expiry: expiry,
        appLabel: appLabel != null ? _hexToAscii(appLabel) : null,
      );
    } on PlatformException catch (e) {
      throw Exception(e.message ?? 'NFC read failed');
    } finally {
      await FlutterNfcKit.finish();
    }
  }

  // ── EMV TLV parsing helpers ────────────────────────────────────────────────

  // Finds the first AID (tag 4F) inside a FCI response from PPSE.
  static String? _extractFirstAid(String hex) {
    return _extractTag(hex, '4F');
  }

  // Extracts the value of a primitive BER-TLV tag from a hex string.
  static String? _extractTag(String hex, String tag) {
    final upper = hex.toUpperCase();
    final t = tag.toUpperCase();
    int i = 0;
    while (i < upper.length - 4) {
      final currentTag = upper.substring(i, i + t.length);
      if (currentTag == t) {
        final lenHex = upper.substring(i + t.length, i + t.length + 2);
        final len = int.tryParse(lenHex, radix: 16);
        if (len == null) return null;
        final start = i + t.length + 2;
        final end = start + len * 2;
        if (end > upper.length) return null;
        return upper.substring(start, end);
      }
      i += 2;
    }
    return null;
  }

  // Card brand from AID prefix.
  static String _brandFromAid(String aid) {
    final a = aid.toUpperCase();
    if (a.startsWith('A0000000031010') || a.startsWith('A0000000032010')) return 'VISA';
    if (a.startsWith('A0000000041010') || a.startsWith('A0000000042010')) return 'MASTERCARD';
    if (a.startsWith('A00000002501')) return 'AMEX';
    if (a.startsWith('A0000000065010')) return 'JCB';
    if (a.startsWith('A0000001523010')) return 'MADA';
    return 'UNKNOWN';
  }

  // Extracts last 4 digits from Track 2 Equivalent Data (tag 57).
  // Format: PAN D YYMM SC DD — D is separator
  static String? _last4FromTrack2(String? track2) {
    if (track2 == null) return null;
    final sep = track2.toUpperCase().indexOf('D');
    if (sep < 4) return null;
    final pan = track2.substring(0, sep);
    return pan.length >= 4 ? pan.substring(pan.length - 4) : null;
  }

  // Extracts expiry from Track 2: YYMM → MM/20YY
  static String? _expiryFromTrack2(String? track2) {
    if (track2 == null) return null;
    final sep = track2.toUpperCase().indexOf('D');
    if (sep < 0 || track2.length < sep + 5) return null;
    final yy = track2.substring(sep + 1, sep + 3);
    final mm = track2.substring(sep + 3, sep + 5);
    return '$mm/20$yy';
  }

  static String _hexLen(String hex) =>
      (hex.length ~/ 2).toRadixString(16).padLeft(2, '0').toUpperCase();

  static String _hexToAscii(String hex) {
    final sb = StringBuffer();
    for (int i = 0; i < hex.length - 1; i += 2) {
      final code = int.tryParse(hex.substring(i, i + 2), radix: 16);
      if (code != null && code >= 32 && code < 127) sb.writeCharCode(code);
    }
    return sb.toString().trim();
  }
}
