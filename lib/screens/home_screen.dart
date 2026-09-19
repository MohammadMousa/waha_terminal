import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/local_prefs.dart';
import '../state/terminal_state.dart';
import 'config_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _checkConfig();
    });
  }

  void _checkConfig() {
    if (LocalPrefs.apiBaseUrl == null) {
      _openConfig();
    }
  }

  Future<void> _openConfig() async {
    final reinit = await Navigator.push<bool>(
      context,
      MaterialPageRoute(builder: (_) => const ConfigScreen()),
    );
    if (reinit == true && mounted) {
      context.read<TerminalState>().init();
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = context.watch<TerminalState>();
    final scheme = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Waha Terminal'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            onPressed: _openConfig,
          ),
        ],
      ),
      body: AnimatedSwitcher(
        duration: const Duration(milliseconds: 300),
        child: _buildBody(state, scheme),
      ),
    );
  }

  Widget _buildBody(TerminalState state, ColorScheme scheme) {
    switch (state.status) {
      case TerminalStatus.idle:
        return _IdleView(key: const ValueKey('idle'), linkStatus: state.linkStatusText);

      case TerminalStatus.pending:
      case TerminalStatus.reading:
        return _PendingView(
          key: const ValueKey('pending'),
          state: state,
          scheme: scheme,
        );

      case TerminalStatus.confirmed:
        return _ResultView(
          key: const ValueKey('confirmed'),
          icon: Icons.check_circle_outline,
          color: scheme.primary,
          title: 'Payment Accepted',
          subtitle: 'Card read successfully',
          onDismiss: state.dismissResult,
        );

      case TerminalStatus.timeout:
        return _ResultView(
          key: const ValueKey('timeout'),
          icon: Icons.timer_off_outlined,
          color: scheme.error,
          title: 'Session Timed Out',
          subtitle: 'No card was presented in time',
          onDismiss: state.dismissResult,
        );

      case TerminalStatus.cancelled:
        return _ResultView(
          key: const ValueKey('cancelled'),
          icon: Icons.cancel_outlined,
          color: scheme.outline,
          title: 'Cancelled',
          subtitle: 'Payment was cancelled',
          onDismiss: state.dismissResult,
        );

      case TerminalStatus.error:
        return _ResultView(
          key: const ValueKey('error'),
          icon: Icons.error_outline,
          color: scheme.error,
          title: 'Error',
          subtitle: state.errorMessage ?? 'An unexpected error occurred',
          onDismiss: state.dismissResult,
        );
    }
  }
}

class _IdleView extends StatelessWidget {
  final String? linkStatus;
  const _IdleView({super.key, this.linkStatus});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final configured = LocalPrefs.apiBaseUrl != null;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.contactless_outlined, size: 80, color: scheme.outline),
          const SizedBox(height: 24),
          Text('Ready', style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 8),
          Text(
            configured ? 'Waiting for payment…' : 'Not configured — open Settings',
            style: TextStyle(color: scheme.outline),
          ),
          if (linkStatus != null) ...[
            const SizedBox(height: 16),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Text(
                linkStatus!,
                textAlign: TextAlign.center,
                style: TextStyle(color: scheme.primary, fontSize: 13),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _PendingView extends StatelessWidget {
  final TerminalState state;
  final ColorScheme scheme;

  const _PendingView({super.key, required this.state, required this.scheme});

  @override
  Widget build(BuildContext context) {
    final isReading = state.status == TerminalStatus.reading;
    final amountText = state.amount != null
        ? '${state.amount!.toStringAsFixed(2)} ${state.currency ?? ''}'
        : '—';

    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Animated card icon
            TweenAnimationBuilder<double>(
              tween: Tween(begin: 0.9, end: 1.05),
              duration: const Duration(milliseconds: 800),
              builder: (ctx, v, child) => Transform.scale(scale: v, child: child),
              onEnd: () {},
              child: Icon(
                Icons.nfc,
                size: 100,
                color: isReading ? scheme.primary : scheme.outline,
              ),
            ),
            const SizedBox(height: 32),
            Text(
              amountText,
              style: Theme.of(context).textTheme.displaySmall?.copyWith(
                    fontWeight: FontWeight.bold,
                    color: scheme.primary,
                  ),
            ),
            const SizedBox(height: 12),
            Text(
              isReading ? 'Tap your card on the device' : 'Preparing payment…',
              style: Theme.of(context).textTheme.titleMedium,
              textAlign: TextAlign.center,
            ),
            if (isReading) ...[
              const SizedBox(height: 8),
              Text(
                'Hold card still until confirmed',
                style: TextStyle(color: scheme.outline),
                textAlign: TextAlign.center,
              ),
            ],
            const SizedBox(height: 40),
            if (isReading)
              const LinearProgressIndicator(),
            const SizedBox(height: 24),
            OutlinedButton.icon(
              icon: const Icon(Icons.cancel_outlined),
              label: const Text('Cancel Payment'),
              onPressed: state.cancel,
              style: OutlinedButton.styleFrom(foregroundColor: scheme.error),
            ),
          ],
        ),
      ),
    );
  }
}

class _ResultView extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String title;
  final String subtitle;
  final VoidCallback onDismiss;

  const _ResultView({
    super.key,
    required this.icon,
    required this.color,
    required this.title,
    required this.subtitle,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 80, color: color),
            const SizedBox(height: 24),
            Text(title,
                style: Theme.of(context)
                    .textTheme
                    .headlineSmall
                    ?.copyWith(color: color)),
            const SizedBox(height: 8),
            Text(subtitle,
                textAlign: TextAlign.center,
                style: TextStyle(color: Theme.of(context).colorScheme.outline)),
            const SizedBox(height: 32),
            FilledButton(
              onPressed: onDismiss,
              child: const Text('Done'),
            ),
          ],
        ),
      ),
    );
  }
}
