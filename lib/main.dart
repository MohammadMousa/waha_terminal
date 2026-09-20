import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'screens/config_screen.dart';
import 'screens/home_screen.dart';
import 'screens/login_screen.dart';
import 'services/api_client.dart';
import 'services/local_prefs.dart';
import 'services/screen_service.dart';
import 'services/terminal_auth_service.dart';
import 'state/terminal_state.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await LocalPrefs.init();
  ScreenService.apply();

  final api = ApiClient();
  final state = TerminalState(api);

  final authenticated = await TerminalAuthService.resolveStartupAuth(api);
  if (authenticated) await state.init();

  runApp(WahaTerminalApp(
    api: api,
    state: state,
    startAuthenticated: authenticated,
  ));
}

class WahaTerminalApp extends StatelessWidget {
  final ApiClient api;
  final TerminalState state;
  final bool startAuthenticated;

  const WahaTerminalApp({
    super.key,
    required this.api,
    required this.state,
    required this.startAuthenticated,
  });

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        Provider<ApiClient>.value(value: api),
        ChangeNotifierProvider<TerminalState>.value(value: state),
      ],
      child: MaterialApp(
        title: 'Waha Terminal',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          colorSchemeSeed: const Color(0xFF6B1A2A),
          useMaterial3: true,
        ),
        routes: {
          '/config': (_) => const ConfigScreen(),
        },
        home: startAuthenticated ? const HomeScreen() : const LoginScreen(),
      ),
    );
  }
}
