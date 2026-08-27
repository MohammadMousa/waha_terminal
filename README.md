# Waha Terminal

Standalone Flutter app acting as a software POS terminal for Waha. Runs alongside
the Waha Kiosk — on the same device or a second device — and handles card-present
payment via NFC. Provider-agnostic: a fake provider simulates the flow today, a
real aggregator SDK (Ingenico, PAX, etc.) drops in later via the `TerminalProvider`
interface without changing the app's architecture.

## How it fits together

```
[Waha Kiosk]  ──── HTTP ────►  [Waha Backend]  ◄──── HTTP ────  [Waha Terminal]
   customer UI                  Spring Boot          cashier / POS UI
   picks "Terminal"             owns session state   reads NFC, confirms
```

1. Customer picks "Pay by Terminal" on the kiosk invoice screen.
2. Backend creates a payment session, status `PENDING_TERMINAL`.
3. This app polls the backend, shows amount + "Tap card".
4. Customer taps an NFC card; the app reads the tag and confirms to the backend.
5. Backend marks the session `PAID`; kiosk picks it up on its next poll.
6. 90s with no tap → session times out, retry allowed.

## Running locally

The app needs a reachable Waha backend (defaults to `http://10.0.2.2:8081`, the
Android emulator's alias for the host machine's `localhost`).

On a **real device**, the emulator alias won't resolve. Go to
**Terminal Setup → Custom connection**, pick a preset (Emulator / Localhost /
Custom), and for Custom enter the backend host machine's actual LAN IP and port
— not the phone's own IP. The value is saved and reused on subsequent launches.

```
flutter pub get
flutter run
```

## Project layout

```
lib/
  config/app_config.dart
  services/local_prefs.dart
  services/api_client.dart
  services/nfc_service.dart
  state/terminal_session_service.dart
  screens/config_screen.dart
  screens/idle_screen.dart
  screens/pending_screen.dart
  screens/success_screen.dart
  screens/error_screen.dart
  main.dart
```

## Related repos

- `waha` — Spring Boot backend, owns payment session state
- `waha_platform` — Waha Kiosk, customer-facing Flutter app
