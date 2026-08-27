import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/api_client.dart';
import '../services/local_prefs.dart';
import '../services/terminal_auth_service.dart';
import '../state/terminal_state.dart';
import 'home_screen.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _userCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  bool _loading = false;
  String? _error;
  bool _obscure = true;

  @override
  void dispose() {
    _userCtrl.dispose();
    _passCtrl.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    final u = _userCtrl.text.trim();
    final p = _passCtrl.text;
    if (u.isEmpty || p.isEmpty) {
      setState(() => _error = 'Username and password are required');
      return;
    }
    setState(() { _loading = true; _error = null; });
    try {
      final api = context.read<ApiClient>();
      await TerminalAuthService.login(api, u, p);
      if (!mounted) return;
      // Auth done — start terminal polling then go to HomeScreen.
      await context.read<TerminalState>().init();
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => const HomeScreen()),
      );
    } on UnauthorizedException {
      setState(() { _loading = false; _error = 'Invalid username or password'; });
    } catch (e) {
      final url = LocalPrefs.apiBaseUrl ?? LocalPrefs.defaultApiBaseUrl;
      setState(() {
        _loading = false;
        _error = 'Cannot reach server at $url\nOn a real device, use the LAN IP of the server.';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(32),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 400),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Icon(Icons.contactless_outlined, size: 64, color: scheme.primary),
                const SizedBox(height: 16),
                Text(
                  'Waha Terminal',
                  textAlign: TextAlign.center,
                  style: Theme.of(context)
                      .textTheme
                      .headlineSmall
                      ?.copyWith(fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 8),
                Text(
                  'Sign in to start accepting payments',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: scheme.outline),
                ),
                const SizedBox(height: 40),

                TextField(
                  controller: _userCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Username',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.person_outline),
                  ),
                  textInputAction: TextInputAction.next,
                  autofillHints: const [AutofillHints.username],
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: _passCtrl,
                  decoration: InputDecoration(
                    labelText: 'Password',
                    border: const OutlineInputBorder(),
                    prefixIcon: const Icon(Icons.lock_outline),
                    suffixIcon: IconButton(
                      icon: Icon(_obscure ? Icons.visibility_off_outlined : Icons.visibility_outlined),
                      onPressed: () => setState(() => _obscure = !_obscure),
                    ),
                  ),
                  obscureText: _obscure,
                  textInputAction: TextInputAction.done,
                  onSubmitted: (_) => _login(),
                  autofillHints: const [AutofillHints.password],
                ),

                if (_error != null) ...[
                  const SizedBox(height: 12),
                  Text(_error!, style: TextStyle(color: scheme.error), textAlign: TextAlign.center),
                ],

                const SizedBox(height: 24),
                FilledButton(
                  onPressed: _loading ? null : _login,
                  child: _loading
                      ? const SizedBox(height: 20, width: 20,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : const Text('Sign In'),
                ),

                const SizedBox(height: 16),
                TextButton.icon(
                  icon: const Icon(Icons.settings_outlined, size: 18),
                  label: const Text('Backend Settings'),
                  onPressed: () => Navigator.pushNamed(context, '/config'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
