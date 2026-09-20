import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import '../services/local_prefs.dart';

enum _ServerPreset {
  emulator('Emulator (10.0.2.2)', '10.0.2.2', 8081),
  localhost('Localhost', 'localhost', 8081),
  custom('Custom', '', 8081);

  final String label;
  final String defaultHost;
  final int defaultPort;
  const _ServerPreset(this.label, this.defaultHost, this.defaultPort);
}

class ConfigScreen extends StatefulWidget {
  const ConfigScreen({super.key});

  @override
  State<ConfigScreen> createState() => _ConfigScreenState();
}

class _ConfigScreenState extends State<ConfigScreen> {
  final _hostCtrl = TextEditingController();
  final _portCtrl = TextEditingController(text: '8081');
  String        _connType   = 'same_device';
  String        _debugLevel = 'minimal';
  bool          _customUrl  = false;
  bool          _saving     = false;
  _ServerPreset _preset     = _ServerPreset.custom;

  @override
  void initState() {
    super.initState();
    _customUrl  = LocalPrefs.customUrlEnabled;
    _connType   = LocalPrefs.connectionType;
    _debugLevel = LocalPrefs.debugLevel;

    final stored = LocalPrefs.apiBaseUrl;
    final toParse = (stored != null && stored.isNotEmpty)
        ? stored
        : LocalPrefs.defaultApiBaseUrl;
    final uri = Uri.tryParse(toParse);
    _hostCtrl.text = uri?.host ?? '';
    _portCtrl.text = (uri?.hasPort == true ? uri!.port : 8081).toString();
    _detectPreset();
  }

  @override
  void dispose() {
    _hostCtrl.dispose();
    _portCtrl.dispose();
    super.dispose();
  }

  void _detectPreset() {
    final host = _hostCtrl.text.trim();
    final port = int.tryParse(_portCtrl.text.trim()) ?? 8081;
    for (final p in _ServerPreset.values) {
      if (p != _ServerPreset.custom && p.defaultHost == host && p.defaultPort == port) {
        _preset = p;
        return;
      }
    }
    _preset = _ServerPreset.custom;
  }

  void _selectPreset(_ServerPreset p) {
    setState(() {
      _preset = p;
      if (p != _ServerPreset.custom) {
        _hostCtrl.text = p.defaultHost;
        _portCtrl.text = p.defaultPort.toString();
      }
    });
  }

  String get _previewUrl {
    final h = _hostCtrl.text.trim();
    final port = _portCtrl.text.trim();
    if (h.isEmpty) return '';
    return 'http://$h:$port';
  }

  Future<bool> _testConnection(String url) async {
    try {
      final resp = await http
          .get(Uri.parse('$url/api/config'))
          .timeout(const Duration(seconds: 5));
      return resp.statusCode < 500;
    } catch (_) {
      return false;
    }
  }

  Future<void> _save() async {
    final effectiveUrl = _customUrl ? _previewUrl : LocalPrefs.defaultApiBaseUrl;

    if (_customUrl && effectiveUrl.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Host is required')),
      );
      return;
    }

    setState(() => _saving = true);

    // Test connection before saving.
    final reachable = await _testConnection(effectiveUrl);
    if (!mounted) return;

    if (!reachable) {
      setState(() => _saving = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Cannot reach $effectiveUrl — check URL and that the backend is running'),
          duration: const Duration(seconds: 4),
        ),
      );
      return;
    }

    await LocalPrefs.setCustomUrlEnabled(_customUrl);
    if (_customUrl) await LocalPrefs.setApiBaseUrl(effectiveUrl);
    await LocalPrefs.setConnectionType(_connType);
    await LocalPrefs.setDebugLevel(_debugLevel);

    if (mounted) {
      setState(() => _saving = false);
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Connected and saved')));
      Navigator.pop(context, true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final fieldsEnabled = _customUrl && _preset == _ServerPreset.custom;
    return Scaffold(
      appBar: AppBar(title: const Text('Terminal Setup')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          // ── Connection type ─────────────────────────────────────────────
          Text('Connection Type', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          for (final opt in [
            ('same_device', 'Same Device',  'NFC on this device'),
            ('usb',         'USB',          'USB cable to the kiosk (this phone acts as the terminal)'),
            ('bluetooth',   'Bluetooth',    'BT pairing to hardware terminal (future)'),
          ])
            RadioListTile<String>(
              title: Text(opt.$2),
              subtitle: Text(opt.$3, style: TextStyle(color: scheme.outline, fontSize: 12)),
              value: opt.$1,
              groupValue: _connType,
              onChanged: (v) => setState(() => _connType = v!),
            ),

          const SizedBox(height: 24),

          // ── Backend URL ─────────────────────────────────────────────────
          Text('Backend URL', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 4),

          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.circle, size: 8,
                    color: _customUrl ? scheme.outlineVariant : scheme.primary),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Default: ${LocalPrefs.defaultApiBaseUrl}',
                        style: TextStyle(
                          color: _customUrl ? scheme.outline : scheme.onSurface,
                          fontFamily: 'monospace',
                        ),
                      ),
                      Text(
                        'Emulator only — use custom URL on a real device',
                        style: TextStyle(color: scheme.outline, fontSize: 11),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),

          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Custom connection'),
            subtitle: Text(
              _customUrl ? 'Using custom backend URL' : 'Using default (emulator only)',
              style: TextStyle(color: scheme.outline, fontSize: 12),
            ),
            value: _customUrl,
            onChanged: (v) => setState(() => _customUrl = v),
          ),

          if (_customUrl) ...[
            const SizedBox(height: 8),
            DropdownButtonFormField<_ServerPreset>(
              initialValue: _preset,
              decoration: const InputDecoration(
                labelText: 'Preset',
                border: OutlineInputBorder(),
              ),
              items: _ServerPreset.values
                  .map((p) => DropdownMenuItem(value: p, child: Text(p.label)))
                  .toList(),
              onChanged: (p) => _selectPreset(p!),
            ),
            const SizedBox(height: 8),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  flex: 3,
                  child: TextField(
                    controller: _hostCtrl,
                    enabled: fieldsEnabled,
                    decoration: const InputDecoration(
                      labelText: 'Host',
                      hintText: '192.168.1.x',
                      border: OutlineInputBorder(),
                    ),
                    keyboardType: TextInputType.url,
                    autocorrect: false,
                    onChanged: (_) => setState(_detectPreset),
                  ),
                ),
                const SizedBox(width: 8),
                SizedBox(
                  width: 100,
                  child: TextField(
                    controller: _portCtrl,
                    enabled: fieldsEnabled,
                    decoration: const InputDecoration(
                      labelText: 'Port',
                      hintText: '8081',
                      border: OutlineInputBorder(),
                    ),
                    keyboardType: TextInputType.number,
                    onChanged: (_) => setState(_detectPreset),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              'Emulator only works from the Android emulator. Localhost only '
              'works if the backend runs on this same device. On a real '
              "device or a physical terminal, pick Custom and type the "
              "backend machine's own LAN IP — not this device's.",
              style: TextStyle(color: scheme.outline, fontSize: 11),
            ),
            if (_previewUrl.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                _previewUrl,
                style: TextStyle(
                    fontFamily: 'monospace', fontSize: 12, color: scheme.primary),
              ),
            ],
          ],

          const SizedBox(height: 24),

          // ── Card data detail ────────────────────────────────────────────
          Text('Card Data Detail', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          for (final opt in [
            ('minimal', 'Minimal', 'brand + last 4 digits only'),
            ('full',    'Full',    '+ UID, expiry, entry method, app label'),
          ])
            RadioListTile<String>(
              title: Text(opt.$2),
              subtitle: Text(opt.$3, style: TextStyle(color: scheme.outline, fontSize: 12)),
              value: opt.$1,
              groupValue: _debugLevel,
              onChanged: (v) => setState(() => _debugLevel = v!),
            ),

          const SizedBox(height: 32),
          FilledButton(
            onPressed: _saving ? null : _save,
            child: _saving
                ? const SizedBox(
                    height: 18, width: 18,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Save & Connect'),
          ),
        ],
      ),
    );
  }
}
